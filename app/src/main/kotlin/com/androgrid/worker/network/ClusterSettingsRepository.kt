package com.androgrid.worker.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository interface governing persistent dynamic network endpoints and mTLS credentials.
 *
 * Traces to: [INV-001], [INV-002], [REQ-OP-001], [REQ-OP-003], [NFR-SEC-001]
 */
interface ClusterSettingsRepository {
    /**
     * Hot observable stream emitting configuration changes to subscribed services.
     */
    val settingsFlow: StateFlow<ClusterSettings>

    /**
     * Reads current configuration snapshot synchronously.
     */
    fun getSettings(): ClusterSettings

    /**
     * Overwrites entire configuration snapshot atomically.
     */
    fun updateSettings(settings: ClusterSettings)

    /**
     * Updates local Wi-Fi orchestrator endpoint.
     */
    fun updateWifiEndpoint(host: String, port: Int)

    /**
     * Updates WAN cellular ingress domain and port.
     */
    fun updateCellularEndpoint(host: String, port: Int)

    /**
     * Updates Mutual TLS cryptographic certificate materials.
     */
    fun updateCertificates(caCertPem: String, clientCertPem: String, clientKeyPem: String)

    /**
     * Clears all cryptographic certificates and private keys.
     */
    fun clearCertificates()

    /**
     * Restores default cluster endpoints and empties cryptographic materials.
     */
    fun resetDefaults()
}

/**
 * Production implementation of [ClusterSettingsRepository] backed by Android SharedPreferences.
 * Stores credentials in private app-internal sandbox storage.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
class SharedPreferencesClusterSettingsRepository(
    context: Context
) : ClusterSettingsRepository {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settingsFlow = MutableStateFlow(readFromPrefs())
    override val settingsFlow: StateFlow<ClusterSettings> = _settingsFlow.asStateFlow()

    override fun getSettings(): ClusterSettings = _settingsFlow.value

    override fun updateSettings(settings: ClusterSettings) {
        prefs.edit()
            .putString(KEY_WIFI_HOST, settings.wifiHost)
            .putInt(KEY_WIFI_PORT, settings.wifiPort)
            .putString(KEY_CELLULAR_HOST, settings.cellularHost)
            .putInt(KEY_CELLULAR_PORT, settings.cellularPort)
            .putString(KEY_CA_CERT, settings.caCertPem)
            .putString(KEY_CLIENT_CERT, settings.clientCertPem)
            .putString(KEY_CLIENT_KEY, settings.clientKeyPem)
            .apply()
        _settingsFlow.value = settings
    }

    override fun updateWifiEndpoint(host: String, port: Int) {
        val updated = _settingsFlow.value.copy(wifiHost = host, wifiPort = port)
        updateSettings(updated)
    }

    override fun updateCellularEndpoint(host: String, port: Int) {
        val updated = _settingsFlow.value.copy(cellularHost = host, cellularPort = port)
        updateSettings(updated)
    }

    override fun updateCertificates(caCertPem: String, clientCertPem: String, clientKeyPem: String) {
        val updated = _settingsFlow.value.copy(
            caCertPem = caCertPem,
            clientCertPem = clientCertPem,
            clientKeyPem = clientKeyPem
        )
        updateSettings(updated)
    }

    override fun clearCertificates() {
        updateCertificates("", "", "")
    }

    override fun resetDefaults() {
        updateSettings(ClusterSettings())
    }

    private fun readFromPrefs(): ClusterSettings {
        return ClusterSettings(
            wifiHost = prefs.getString(KEY_WIFI_HOST, ClusterSettings.DEFAULT_WIFI_HOST) ?: ClusterSettings.DEFAULT_WIFI_HOST,
            wifiPort = prefs.getInt(KEY_WIFI_PORT, ClusterSettings.DEFAULT_WIFI_PORT),
            cellularHost = prefs.getString(KEY_CELLULAR_HOST, ClusterSettings.DEFAULT_CELLULAR_HOST) ?: ClusterSettings.DEFAULT_CELLULAR_HOST,
            cellularPort = prefs.getInt(KEY_CELLULAR_PORT, ClusterSettings.DEFAULT_CELLULAR_PORT),
            caCertPem = prefs.getString(KEY_CA_CERT, "") ?: "",
            clientCertPem = prefs.getString(KEY_CLIENT_CERT, "") ?: "",
            clientKeyPem = prefs.getString(KEY_CLIENT_KEY, "") ?: ""
        )
    }

    companion object {
        const val PREFS_NAME: String = "androgrid_cluster_settings"
        private const val KEY_WIFI_HOST: String = "wifi_host"
        private const val KEY_WIFI_PORT: String = "wifi_port"
        private const val KEY_CELLULAR_HOST: String = "cellular_host"
        private const val KEY_CELLULAR_PORT: String = "cellular_port"
        private const val KEY_CA_CERT: String = "ca_cert_pem"
        private const val KEY_CLIENT_CERT: String = "client_cert_pem"
        private const val KEY_CLIENT_KEY: String = "client_key_pem"

        @Volatile
        private var instance: SharedPreferencesClusterSettingsRepository? = null

        /**
         * Returns singleton repository instance.
         */
        fun getInstance(context: Context): SharedPreferencesClusterSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SharedPreferencesClusterSettingsRepository(context).also { instance = it }
            }
        }
    }
}

/**
 * In-memory repository implementation for hermetic unit testing.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
class InMemoryClusterSettingsRepository(
    initialSettings: ClusterSettings = ClusterSettings()
) : ClusterSettingsRepository {

    private val _settingsFlow = MutableStateFlow(initialSettings)
    override val settingsFlow: StateFlow<ClusterSettings> = _settingsFlow.asStateFlow()

    override fun getSettings(): ClusterSettings = _settingsFlow.value

    override fun updateSettings(settings: ClusterSettings) {
        _settingsFlow.value = settings
    }

    override fun updateWifiEndpoint(host: String, port: Int) {
        val updated = _settingsFlow.value.copy(wifiHost = host, wifiPort = port)
        updateSettings(updated)
    }

    override fun updateCellularEndpoint(host: String, port: Int) {
        val updated = _settingsFlow.value.copy(cellularHost = host, cellularPort = port)
        updateSettings(updated)
    }

    override fun updateCertificates(caCertPem: String, clientCertPem: String, clientKeyPem: String) {
        val updated = _settingsFlow.value.copy(
            caCertPem = caCertPem,
            clientCertPem = clientCertPem,
            clientKeyPem = clientKeyPem
        )
        updateSettings(updated)
    }

    override fun clearCertificates() {
        updateCertificates("", "", "")
    }

    override fun resetDefaults() {
        updateSettings(ClusterSettings())
    }
}
