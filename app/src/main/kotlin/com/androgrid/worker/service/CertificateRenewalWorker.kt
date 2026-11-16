package com.androgrid.worker.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
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
import com.androgrid.worker.network.CellularClaimOutcome
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
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
            ?: CredentialLifecycleManager(settingsRepo)

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
        val battery = AndroidBatteryStatusProvider(applicationContext).getBatteryStatus()
        val capabilities = DeviceCapabilityProvider(
            StandardRootChecker(),
            AndroidSystemInfoProvider(applicationContext)
        ).buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
            isMetered = true,
            batteryTemperatureCelsius = battery.temperatureCelsius,
            thermalStatus = 0
        )

        lifecycleManager.acquireGeneration(activeAlias)
        return try {
            val activeClient = CellularTransportClient(
                ingressHost = settings.cellularHost,
                ingressPort = settings.cellularPort,
                sslSocketFactory = mtlsContext.socketFactory
            )

            val outcome = lifecycleManager.renewIfDue(
                nodeId = nodeId,
                renewRpc = { req -> activeClient.renewCertificate(req) },
                trialCandidateRpc = { candidateAlias, _ ->
                    val candidateContext = AndroidKeyStoreEnrollmentProvider.createDefault()
                        .createMtlsContext(candidateAlias, settings.caCertPem)
                    val candidateClient = CellularTransportClient(
                        ingressHost = settings.cellularHost,
                        ingressPort = settings.cellularPort,
                        sslSocketFactory = candidateContext.socketFactory
                    )
                    val claimReq = ClaimCellularTaskRequest.newBuilder()
                        .setNodeId(nodeId)
                        .setCapabilities(capabilities)
                        .setBatteryLevelPct(battery.levelPercent)
                        .setIsCharging(battery.isCharging)
                        .setBatteryTemperatureCelsius(battery.temperatureCelsius)
                        .build()
                    val claimResult = candidateClient.claimTask(claimReq)
                    claimResult
                }
            )

            when (outcome) {
                is RenewalOutcome.NotDue,
                is RenewalOutcome.Renewed<*> -> Result.success()
                is RenewalOutcome.BackedOff -> Result.success()
                is RenewalOutcome.Failed -> if (outcome.terminal) Result.failure() else Result.retry()
            }
        } finally {
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

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
