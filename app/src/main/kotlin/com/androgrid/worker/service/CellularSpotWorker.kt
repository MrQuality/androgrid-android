package com.androgrid.worker.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import io.grpc.ManagedChannel

/**
 * Pure testable logic for cellular spot worker execution.
 * Enforces battery preconditions ([REQ-OP-003]), offline execution ([INV-003]),
 * and atomic result submission ([REQ-OP-004]).
 *
 * Traces to: [INV-001], [INV-002], [INV-003], [REQ-OP-003], [REQ-OP-004], [NFR-POW-001], [NFR-SEC-001]
 */
class CellularSpotWorkerLogic(
    private val nodeId: String,
    private val batteryProvider: BatteryStatusProvider,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val cellularClient: CellularTransportClient = CellularTransportClient(),
    private val dispatcher: TaskExecutionDispatcher = TaskExecutionDispatcher(),
    private val channelProvider: (() -> ManagedChannel)? = null
) {
    /**
     * Executes single spot task cycle: battery check -> claim -> execute -> submit.
     *
     * @return Result.success() on completion or stale attempt; Result.retry() on transient errors.
     */
    suspend fun executeSpotTask(): androidx.work.ListenableWorker.Result {
        // Step 1: Precondition check per [REQ-OP-003] (battery >= 20% or charging)
        val battery = batteryProvider.getBatteryStatus()
        if (battery.levelPercent < 20 && !battery.isCharging) {
            return androidx.work.ListenableWorker.Result.success()
        }

        // Step 2: Build claim request
        val capabilities = capabilityProvider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
            isMetered = true
        )

        val claimRequest = ClaimCellularTaskRequest.newBuilder()
            .setNodeId(nodeId)
            .setCapabilities(capabilities)
            .setBatteryLevelPct(battery.levelPercent)
            .setIsCharging(battery.isCharging)
            .build()

        // Step 3: Unary task claim over ephemeral channel (channel closes immediately)
        val claimResult = if (channelProvider != null) {
            val channel = channelProvider.invoke()
            cellularClient.claimTaskWithChannel(channel, claimRequest)
        } else {
            cellularClient.claimTask(claimRequest)
        }

        if (claimResult.isFailure) {
            return androidx.work.ListenableWorker.Result.retry()
        }

        val claimResponse = claimResult.getOrNull()
        if (claimResponse == null || !claimResponse.taskAvailable) {
            return androidx.work.ListenableWorker.Result.success()
        }

        // Step 4: Offline compute execution via sandboxed dispatcher
        val task = claimResponse.task
        val executionResult = dispatcher.dispatch(task)

        // Step 5: Atomic result submission over new ephemeral channel
        val submitRequest = executionResult.toSubmitCellularResultRequest(
            nodeId = nodeId,
            taskId = task.taskId,
            runAttempt = task.runAttempt
        )

        val submitResult = if (channelProvider != null) {
            val channel = channelProvider.invoke()
            cellularClient.submitResultWithChannel(channel, submitRequest)
        } else {
            cellularClient.submitResult(submitRequest)
        }

        // Even if result was rejected due to preemption (TASK_ATTEMPT_STALE),
        // worker returns success to complete WorkManager cycle
        return if (submitResult.isSuccess || isStaleAttemptError(submitResult.exceptionOrNull())) {
            androidx.work.ListenableWorker.Result.success()
        } else {
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    private fun isStaleAttemptError(throwable: Throwable?): Boolean {
        return throwable?.message?.contains("TASK_ATTEMPT_STALE") == true
    }
}

/**
 * Android Jetpack CoroutineWorker for cellular spot task execution.
 * Enforces zero-keepalive radio sleep and background WorkManager constraints.
 *
 * Traces to: [INV-001], [INV-002], [INV-003], [REQ-OP-003], [REQ-OP-004], [NFR-POW-001], [NFR-SEC-001]
 */
class CellularSpotWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val logic by lazy {
        val rootChecker = StandardRootChecker()
        val systemInfo = AndroidSystemInfoProvider(appContext)
        val capabilityProvider = DeviceCapabilityProvider(rootChecker, systemInfo)
        val batteryProvider = AndroidBatteryStatusProvider(appContext)

        val settingsRepo = SharedPreferencesClusterSettingsRepository.getInstance(appContext)
        val settings = settingsRepo.getSettings()
        val mtlsContext = DefaultMtlsSocketFactoryProvider.buildSocketFactory(settings)
        val cellularClient = CellularTransportClient(
            ingressHost = settings.cellularHost,
            ingressPort = settings.cellularPort,
            sslSocketFactory = mtlsContext?.socketFactory
        )

        CellularSpotWorkerLogic(
            nodeId = "node_${android.os.Build.SERIAL.takeIf { it != "unknown" } ?: "android_worker"}",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = cellularClient
        )
    }

    override suspend fun doWork(): Result {
        return logic.executeSpotTask()
    }

    companion object {
        const val WORK_NAME: String = "androgrid_cellular_spot_work"

        /**
         * Builds constrained WorkRequest for cellular spot execution.
         * Enforces connected network constraint.
         */
        fun buildWorkRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<CellularSpotWorker>()
                .setConstraints(constraints)
                .build()
        }
    }
}
