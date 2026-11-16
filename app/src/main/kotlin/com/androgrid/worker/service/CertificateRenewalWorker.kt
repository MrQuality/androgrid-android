package com.androgrid.worker.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.AndroGridApplication
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.NodeIdProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.enrollment.AndroidKeyStoreEnrollmentProvider
import com.androgrid.worker.enrollment.CredentialLifecycleManager
import com.androgrid.worker.enrollment.RenewalOutcome
import com.androgrid.worker.network.ArtifactResolver
import com.androgrid.worker.network.CellularClaimOutcome
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.telemetry.AndroidDeviceThermalStatusListener
import com.androgrid.worker.telemetry.SharedPreferencesTaskLedgerRepository
import java.util.concurrent.TimeUnit

/**
 * Best-effort expiry-directed one-shot WorkManager job for idle cellular-only workers.
 * Uses [ExistingWorkPolicy.KEEP] so scheduling a successor never cancels an active renewal.
 *
 * Traces to: [INV-002], [INV-003], [ANTI-001], [NFR-POW-001], [REQ-OP-005]
 */
class CertificateRenewalWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AndroGridApplication
        val settingsRepo = app?.settingsRepository
            ?: SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)
        val lifecycleManager = app?.credentialLifecycleManager
            ?: CredentialLifecycleManager(
                settingsRepository = settingsRepo,
                onScheduleRenewal = { delayMs -> schedule(applicationContext, delayMs) }
            )

        lifecycleManager.reconcileOnStartup()

        val settings = settingsRepo.getSettings()
        if (!settings.hasMtlsCredentials) {
            return Result.success()
        }

        val activeAlias = settings.clientKeyPem.removePrefix("keystore:")
            .takeIf { settings.clientKeyPem.startsWith("keystore:") }
            ?: AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS

        val mtlsContext = DefaultMtlsSocketFactoryProvider.buildSocketFactory(settings)
            ?: return Result.success()

        val nodeId = NodeIdProvider.getOrCreateNodeId(applicationContext)
        val batteryProvider = AndroidBatteryStatusProvider(applicationContext)
        val battery = batteryProvider.getBatteryStatus()
        val capabilityProvider = DeviceCapabilityProvider(
            StandardRootChecker(),
            AndroidSystemInfoProvider(applicationContext)
        )
        val thermalListener = AndroidDeviceThermalStatusListener(applicationContext).apply {
            startListening()
        }
        val artifactResolver = app?.artifactResolver ?: ArtifactResolver()
        val ledgerRepository = app?.taskLedgerRepository
            ?: SharedPreferencesTaskLedgerRepository.getInstance(applicationContext)

        val capabilities = capabilityProvider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
            isMetered = true,
            batteryTemperatureCelsius = battery.temperatureCelsius,
            thermalStatus = thermalListener.currentThermalStatus
        )

        lifecycleManager.acquireGeneration(activeAlias)
        return try {
            val activeClient = CellularTransportClient(
                ingressHost = settings.cellularHost,
                ingressPort = settings.cellularPort,
                sslSocketFactory = mtlsContext.socketFactory,
                artifactResolver = artifactResolver
            )

            val outcome = lifecycleManager.renewIfDue(
                nodeId = nodeId,
                renewRpc = { req -> activeClient.renewCertificate(req) },
                trialCandidateRpc = { candidateAlias, _ ->
                    val candidateContext = lifecycleManager.createCandidateMtlsContext(
                        candidateAlias,
                        settings.caCertPem
                    )
                    val candidateClient = CellularTransportClient(
                        ingressHost = settings.cellularHost,
                        ingressPort = settings.cellularPort,
                        sslSocketFactory = candidateContext.socketFactory,
                        artifactResolver = artifactResolver
                    )
                    val claimReq = ClaimCellularTaskRequest.newBuilder()
                        .setNodeId(nodeId)
                        .setCapabilities(capabilities)
                        .setBatteryLevelPct(battery.levelPercent)
                        .setIsCharging(battery.isCharging)
                        .setBatteryTemperatureCelsius(battery.temperatureCelsius)
                        .setThermalStatus(thermalListener.currentThermalStatus)
                        .build()
                    val claimOutcome = candidateClient.claimAndResolveTask(claimReq)
                    if (claimOutcome is CellularClaimOutcome.TransientFailure) {
                        kotlin.Result.failure(claimOutcome.cause)
                    } else {
                        kotlin.Result.success(claimOutcome)
                    }
                }
            )

            when (outcome) {
                is RenewalOutcome.Renewed<*> -> {
                    val preClaimed = outcome.trialResult as? CellularClaimOutcome
                    if (preClaimed is CellularClaimOutcome.TaskReady ||
                        preClaimed is CellularClaimOutcome.TerminalArtifactFailure
                    ) {
                        val latestSettings = settingsRepo.getSettings()
                        val latestMtls = DefaultMtlsSocketFactoryProvider.buildSocketFactory(latestSettings)
                        if (latestMtls != null) {
                            val submitClient = CellularTransportClient(
                                ingressHost = latestSettings.cellularHost,
                                ingressPort = latestSettings.cellularPort,
                                sslSocketFactory = latestMtls.socketFactory,
                                artifactResolver = artifactResolver
                            )
                            val workerLogic = CellularSpotWorkerLogic(
                                nodeId = nodeId,
                                batteryProvider = batteryProvider,
                                capabilityProvider = capabilityProvider,
                                cellularClient = submitClient,
                                thermalListener = thermalListener,
                                ledgerRepository = ledgerRepository
                            )
                            return workerLogic.executeSpotTask(preClaimed)
                        }
                    }
                    Result.success()
                }
                is RenewalOutcome.NotDue,
                is RenewalOutcome.BackedOff -> Result.success()
                is RenewalOutcome.Failed -> if (outcome.terminal) Result.failure() else Result.retry()
            }
        } finally {
            thermalListener.stopListening()
            lifecycleManager.releaseGeneration(activeAlias)
        }
    }

    companion object {
        const val WORK_NAME: String = "androgrid_cert_renewal_work"

        /**
         * Enqueues a best-effort one-shot renewal job with [ExistingWorkPolicy.KEEP].
         */
        fun schedule(context: Context, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<CertificateRenewalWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            val wm = WorkManager.getInstance(context)
            val isRunning = runCatching {
                wm.getWorkInfosForUniqueWork(WORK_NAME).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            val policy = if (isRunning) {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            } else {
                ExistingWorkPolicy.REPLACE
            }
            wm.enqueueUniqueWork(
                WORK_NAME,
                policy,
                request
            )
        }
    }
}
