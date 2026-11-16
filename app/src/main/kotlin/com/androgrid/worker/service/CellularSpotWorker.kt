package com.androgrid.worker.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.AndroGridApplication
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.NodeIdProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.enrollment.AndroidKeyStoreEnrollmentProvider
import com.androgrid.worker.enrollment.CredentialLifecycleManager
import com.androgrid.worker.enrollment.RenewalOutcome
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.network.ArtifactResolver
import com.androgrid.worker.network.CellularClaimOutcome
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.telemetry.AndroidDeviceThermalStatusListener
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.SharedPreferencesTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerEntry
import com.androgrid.worker.telemetry.TaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.androgrid.worker.telemetry.ThermalSafetyGuard
import io.grpc.ManagedChannel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pure testable logic for cellular spot worker execution.
 * Enforces battery preconditions ([REQ-OP-003]), thermal safety bounds ([INV-003], [NFR-POW-001]),
 * offline execution ([INV-003]), and atomic result submission ([REQ-OP-004]).
 *
 * Traces to: [INV-001], [INV-002], [INV-003], [REQ-OP-003], [REQ-OP-004], [NFR-POW-001], [NFR-SEC-001]
 */
class CellularSpotWorkerLogic(
    private val nodeId: String,
    private val batteryProvider: BatteryStatusProvider,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val cellularClient: CellularTransportClient = CellularTransportClient(),
    private val dispatcher: TaskExecutionDispatcher = TaskExecutionDispatcher(),
    private val channelProvider: (() -> ManagedChannel)? = null,
    private val thermalListener: DeviceThermalStatusListener? = null,
    private val thermalGuard: ThermalSafetyGuard = ThermalSafetyGuard(),
    private val ledgerRepository: TaskLedgerRepository? = null
) {
    /**
     * Executes single spot task cycle: battery & thermal checks -> claim -> execute -> submit.
     * Postpones execution with Result.retry() if battery temperature > 38 C or OS reports thermal throttling.
     *
     * @param preClaimedOutcome Optional [CellularClaimOutcome] already obtained during candidate credential trial.
     * @return Result.success() on completion or stale attempt; Result.retry() on transient errors or thermal throttling.
     */
    suspend fun executeSpotTask(
        preClaimedOutcome: CellularClaimOutcome? = null
    ): androidx.work.ListenableWorker.Result {
        val battery = batteryProvider.getBatteryStatus()
        val thermalStatus = thermalListener?.currentThermalStatus ?: 0

        if (preClaimedOutcome == null) {
            // Step 1: Precondition check per [REQ-OP-003] (battery >= 20% or charging)
            if (battery.levelPercent < 20 && !battery.isCharging) {
                return androidx.work.ListenableWorker.Result.success()
            }

            // Step 2: Client-side thermal safety guard per [INV-003], [NFR-POW-001]
            if (thermalGuard.isThermalThrottled(battery.temperatureCelsius, thermalStatus)) {
                // Postpone compute when device battery temperature > 38 C or OS thermal status elevated
                return androidx.work.ListenableWorker.Result.retry()
            }
        }

        // Step 3 & 4: Use pre-claimed outcome if provided, else build and send claim request
        val claimOutcome = preClaimedOutcome ?: run {
            val capabilities = capabilityProvider.buildCapabilities(
                currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
                isMetered = true,
                batteryTemperatureCelsius = battery.temperatureCelsius,
                thermalStatus = thermalStatus
            )

            val claimRequest = ClaimCellularTaskRequest.newBuilder()
                .setNodeId(nodeId)
                .setCapabilities(capabilities)
                .setBatteryLevelPct(battery.levelPercent)
                .setIsCharging(battery.isCharging)
                .setBatteryTemperatureCelsius(battery.temperatureCelsius)
                .setThermalStatus(thermalStatus)
                .build()

            if (channelProvider != null) {
                val channel = channelProvider.invoke()
                cellularClient.claimAndResolveTaskWithChannel(channel, claimRequest)
            } else {
                cellularClient.claimAndResolveTask(claimRequest)
            }
        }

        val task = when (claimOutcome) {
            is CellularClaimOutcome.NoTask -> {
                return androidx.work.ListenableWorker.Result.success()
            }
            is CellularClaimOutcome.TransientFailure -> {
                return androidx.work.ListenableWorker.Result.retry()
            }
            is CellularClaimOutcome.TerminalArtifactFailure -> {
                if (!claimOutcome.submissionSucceeded) {
                    // Reporting the terminal failure back to the orchestrator failed (e.g. radio drop);
                    // retry via WorkManager instead of leaving the orchestrator lease stranded.
                    return androidx.work.ListenableWorker.Result.retry()
                }
                // Terminal failure reported to orchestrator on the same channel before teardown;
                // record locally and complete WorkManager cycle without retrying.
                withContext(NonCancellable) {
                    ledgerRepository?.recordTaskExecution(
                        TaskLedgerEntry(
                            taskId = claimOutcome.task.taskId,
                            runAttempt = claimOutcome.task.runAttempt,
                            route = "CELLULAR_WAN",
                            status = TaskLedgerStatus.FAILED,
                            exitCode = -1,
                            errorCode = claimOutcome.errorCode,
                            executionDurationMs = 0L,
                            completedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                return androidx.work.ListenableWorker.Result.success()
            }
            is CellularClaimOutcome.TaskReady -> claimOutcome.task
        }

        // Step 5: Offline compute execution via sandboxed dispatcher
        val executionResult = dispatcher.dispatch(task)

        // Step 6: Atomic result submission over new ephemeral channel
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

        val isStale = isStaleAttemptError(submitResult.exceptionOrNull())
        val ledgerStatus = when {
            !executionResult.isSuccess -> TaskLedgerStatus.FAILED
            submitResult.isSuccess -> TaskLedgerStatus.COMPLETED
            isStale -> TaskLedgerStatus.STALE
            else -> TaskLedgerStatus.SUBMISSION_FAILED
        }
        withContext(NonCancellable) {
            ledgerRepository?.recordTaskExecution(
                TaskLedgerEntry(
                    taskId = task.taskId,
                    runAttempt = task.runAttempt,
                    route = "CELLULAR_WAN",
                    status = ledgerStatus,
                    exitCode = executionResult.exitCode,
                    errorCode = executionResult.errorCode,
                    executionDurationMs = executionResult.executionDurationMs,
                    completedAtEpochMs = System.currentTimeMillis()
                )
            )
        }

        // Even if result was rejected due to preemption (TASK_ATTEMPT_STALE),
        // worker returns success to complete WorkManager cycle
        return if (submitResult.isSuccess || isStale) {
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
 * Enforces zero-keepalive radio sleep, thermal safety monitoring, and background WorkManager constraints.
 *
 * Traces to: [INV-001], [INV-002], [INV-003], [REQ-OP-003], [REQ-OP-004], [NFR-POW-001], [NFR-SEC-001]
 */
class CellularSpotWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AndroGridApplication
        val settingsRepo = app?.settingsRepository
            ?: SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)
        val lifecycleManager = app?.credentialLifecycleManager
            ?: CredentialLifecycleManager(settingsRepo)

        lifecycleManager.reconcileOnStartup()

        val settings = settingsRepo.getSettings()
        val mtlsContext = DefaultMtlsSocketFactoryProvider.buildSocketFactory(settings)
        if (mtlsContext == null) {
            // Unenrolled or invalid credentials: refuse unauthenticated network execution per [INV-002]
            return Result.success()
        }

        val activeAlias = settings.clientKeyPem.removePrefix("keystore:")
            .takeIf { settings.clientKeyPem.startsWith("keystore:") }
            ?: AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS

        val nodeId = NodeIdProvider.getOrCreateNodeId(applicationContext)
        val rootChecker = StandardRootChecker()
        val systemInfo = AndroidSystemInfoProvider(applicationContext)
        val capabilityProvider = DeviceCapabilityProvider(rootChecker, systemInfo)
        val batteryProvider = AndroidBatteryStatusProvider(applicationContext)
        val thermalListener = AndroidDeviceThermalStatusListener(applicationContext).apply {
            startListening()
        }
        val ledgerRepository = app?.taskLedgerRepository
            ?: SharedPreferencesTaskLedgerRepository.getInstance(applicationContext)
        val artifactResolver = app?.artifactResolver ?: ArtifactResolver()

        lifecycleManager.acquireGeneration(activeAlias)
        var currentClient = CellularTransportClient(
            ingressHost = settings.cellularHost,
            ingressPort = settings.cellularPort,
            sslSocketFactory = mtlsContext.socketFactory,
            artifactResolver = artifactResolver
        )
        try {
            // Evaluate in-band renewal before claiming a task; if candidate trial claims a task,
            // do not discard it - let normal execution proceed only if not already claimed.
            val renewalOutcome = lifecycleManager.renewIfDue(
                nodeId = nodeId,
                renewRpc = { req -> currentClient.renewCertificate(req) },
                trialCandidateRpc = { candidateAlias, _ ->
                    val candidateCtx = AndroidKeyStoreEnrollmentProvider.createDefault()
                        .createMtlsContext(candidateAlias, settings.caCertPem)
                    val candidateClient = CellularTransportClient(
                        ingressHost = settings.cellularHost,
                        ingressPort = settings.cellularPort,
                        sslSocketFactory = candidateCtx.socketFactory,
                        artifactResolver = artifactResolver
                    )
                    val battery = batteryProvider.getBatteryStatus()
                    val caps = capabilityProvider.buildCapabilities(
                        currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
                        isMetered = true,
                        batteryTemperatureCelsius = battery.temperatureCelsius,
                        thermalStatus = thermalListener.currentThermalStatus
                    )
                    val claimReq = ClaimCellularTaskRequest.newBuilder()
                        .setNodeId(nodeId)
                        .setCapabilities(caps)
                        .setBatteryLevelPct(battery.levelPercent)
                        .setIsCharging(battery.isCharging)
                        .setBatteryTemperatureCelsius(battery.temperatureCelsius)
                        .setThermalStatus(thermalListener.currentThermalStatus)
                        .build()
                    val outcome = candidateClient.claimAndResolveTask(claimReq)
                    if (outcome is CellularClaimOutcome.TransientFailure) {
                        kotlin.Result.failure(outcome.cause)
                    } else {
                        kotlin.Result.success(outcome)
                    }
                }
            )

            // Refresh client with latest settings after renewal attempt
            val latestSettings = settingsRepo.getSettings()
            val latestMtls = DefaultMtlsSocketFactoryProvider.buildSocketFactory(latestSettings)
                ?: return Result.success()
            currentClient = CellularTransportClient(
                ingressHost = latestSettings.cellularHost,
                ingressPort = latestSettings.cellularPort,
                sslSocketFactory = latestMtls.socketFactory,
                artifactResolver = artifactResolver
            )

            val workerLogic = CellularSpotWorkerLogic(
                nodeId = nodeId,
                batteryProvider = batteryProvider,
                capabilityProvider = capabilityProvider,
                cellularClient = currentClient,
                thermalListener = thermalListener,
                ledgerRepository = ledgerRepository
            )

            val preClaimed = (renewalOutcome as? RenewalOutcome.Renewed<*>)?.trialResult as? CellularClaimOutcome
            return workerLogic.executeSpotTask(preClaimed)
        } finally {
            thermalListener.stopListening()
            lifecycleManager.releaseGeneration(activeAlias)
        }
    }

    companion object {
        const val WORK_NAME: String = "androgrid_cellular_spot_work"

        /**
         * Builds constrained WorkRequest for cellular spot execution.
         * Enforces connected network constraint and exponential backoff retry policy.
         */
        fun buildWorkRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<CellularSpotWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
        }
    }
}
