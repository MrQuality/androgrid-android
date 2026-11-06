package com.androgrid.worker.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.androgrid.worker.network.ClusterSettingsRepository
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.service.AndroidServiceController
import com.androgrid.worker.service.AndroidWorkScheduler
import com.androgrid.worker.service.ServiceController
import com.androgrid.worker.service.WorkScheduler
import java.io.File

/**
 * Headless BroadcastReceiver facilitating automated physical device and emulator cluster testing via ADB.
 * Allows remote orchestration to configure network endpoints, inject PKI certificates, and toggle compute state.
 *
 * Enforces security boundary: Automation commands are strictly rejected on non-debuggable release builds.
 * Adheres to [INV-001]: Emits no listening network sockets, responding exclusively via Android Binder IPC.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001], [REQ-OP-002]
 */
class TestAutomationReceiver(
    private val settingsRepositoryProvider: (Context) -> ClusterSettingsRepository = {
        SharedPreferencesClusterSettingsRepository.getInstance(it)
    },
    private val serviceControllerProvider: (Context) -> ServiceController = {
        AndroidServiceController(it)
    },
    private val workSchedulerProvider: (Context) -> WorkScheduler = {
        AndroidWorkScheduler(it)
    }
) : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIGURE_ENDPOINT: String = "com.androgrid.worker.action.CONFIGURE_ENDPOINT"
        const val ACTION_START_PARTICIPATION: String = "com.androgrid.worker.action.START_PARTICIPATION"
        const val ACTION_STOP_PARTICIPATION: String = "com.androgrid.worker.action.STOP_PARTICIPATION"

        const val EXTRA_WIFI_HOST: String = "wifi_host"
        const val EXTRA_WIFI_PORT: String = "wifi_port"
        const val EXTRA_CELLULAR_HOST: String = "cellular_host"
        const val EXTRA_CELLULAR_PORT: String = "cellular_port"
        const val EXTRA_PKI_DIR: String = "pki_dir"
        const val EXTRA_CA_CERT_PATH: String = "ca_cert_path"
        const val EXTRA_CLIENT_CERT_PATH: String = "client_cert_path"
        const val EXTRA_CLIENT_KEY_PATH: String = "client_key_path"
        const val EXTRA_CA_CERT_PEM: String = "ca_cert_pem"
        const val EXTRA_CLIENT_CERT_PEM: String = "client_cert_pem"
        const val EXTRA_CLIENT_KEY_PEM: String = "client_key_pem"
        const val EXTRA_AUTO_START: String = "auto_start"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        // Security boundary: Only allow headless test automation in debug builds
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            return
        }

        val action = intent.action ?: return
        val repo = settingsRepositoryProvider(context)
        val serviceController = serviceControllerProvider(context)
        val workScheduler = workSchedulerProvider(context)

        when (action) {
            ACTION_CONFIGURE_ENDPOINT -> {
                handleConfigureEndpoint(context, intent, repo, serviceController)
            }
            ACTION_START_PARTICIPATION -> {
                serviceController.startWifiSyncService()
            }
            ACTION_STOP_PARTICIPATION -> {
                serviceController.stopWifiSyncService()
                workScheduler.cancelCellularSpotWork()
            }
        }
    }

    private fun handleConfigureEndpoint(
        context: Context,
        intent: Intent,
        repo: ClusterSettingsRepository,
        serviceController: ServiceController
    ) {
        val current = repo.getSettings()

        val wifiHost = intent.getStringExtra(EXTRA_WIFI_HOST) ?: current.wifiHost
        val wifiPort = intent.getIntExtra(EXTRA_WIFI_PORT, current.wifiPort)
        val cellularHost = intent.getStringExtra(EXTRA_CELLULAR_HOST) ?: current.cellularHost
        val cellularPort = intent.getIntExtra(EXTRA_CELLULAR_PORT, current.cellularPort)

        var caCertPem = intent.getStringExtra(EXTRA_CA_CERT_PEM) ?: current.caCertPem
        var clientCertPem = intent.getStringExtra(EXTRA_CLIENT_CERT_PEM) ?: current.clientCertPem
        var clientKeyPem = intent.getStringExtra(EXTRA_CLIENT_KEY_PEM) ?: current.clientKeyPem

        // Step 1: Update network endpoints
        repo.updateSettings(
            current.copy(
                wifiHost = wifiHost,
                wifiPort = wifiPort,
                cellularHost = cellularHost,
                cellularPort = cellularPort
            )
        )

        // Step 2: Ingest PKI certificates from directory or files if specified
        val pkiDirExtra = intent.getStringExtra(EXTRA_PKI_DIR)
        val caPath = intent.getStringExtra(EXTRA_CA_CERT_PATH)
        val certPath = intent.getStringExtra(EXTRA_CLIENT_CERT_PATH)
        val keyPath = intent.getStringExtra(EXTRA_CLIENT_KEY_PATH)

        if (pkiDirExtra != null) {
            PkiCredentialLoader.loadFromDirectory(File(pkiDirExtra), repo)
        } else if (caPath != null && certPath != null && keyPath != null) {
            PkiCredentialLoader.loadFromPaths(caPath, certPath, keyPath, repo)
        } else if (caCertPem.isNotBlank() && clientCertPem.isNotBlank() && clientKeyPem.isNotBlank()) {
            repo.updateCertificates(caCertPem, clientCertPem, clientKeyPem)
        } else {
            // Check canonical app-private PKI directory
            PkiCredentialLoader.syncCanonicalDirectoryIfPresent(context, repo)
        }

        // Step 3: Handle optional auto-start flag
        val autoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        if (autoStart) {
            serviceController.startWifiSyncService()
        }
    }
}
