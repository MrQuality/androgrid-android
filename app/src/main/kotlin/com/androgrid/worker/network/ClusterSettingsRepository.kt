package com.androgrid.worker.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted lifecycle states for crash-safe mTLS credential rotation.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-005]
 */
enum class CredentialLifecycleState {
    ACTIVE,
    PENDING,
    CANDIDATE_VERIFYING,
    RETIRING
}

/**
 * Durable credential lifecycle record persisted atomically alongside [ClusterSettings].
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-005]
 */
data class CredentialLifecycleRecord(
    val state: CredentialLifecycleState = CredentialLifecycleState.ACTIVE,
    val activeAlias: String = "",
    val stagingAlias: String = "",
    val retiringAlias: String = "",
    val pendingCsrPem: String = "",
    val candidateCertPem: String = "",
    val nextEligibleRenewalEpochSeconds: Long = 0L,
    val consecutiveFailures: Int = 0
)

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

    /**
     * Reads the persisted credential lifecycle record synchronously.
     */
    fun getLifecycleRecord(): CredentialLifecycleRecord = CredentialLifecycleRecord()

    /**
     * Atomically commits both [ClusterSettings] and [CredentialLifecycleRecord] in a single
     * synchronous storage transaction, publishing to [settingsFlow] only after persistence succeeds.
     */
    fun commitSettingsAndLifecycle(
        settings: ClusterSettings,
        record: CredentialLifecycleRecord
    ): Boolean {
        updateSettings(settings)
        return true
    }
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
            .commit()
        _settingsFlow.value = settings
    }

    override fun getLifecycleRecord(): CredentialLifecycleRecord {
        val stateName = prefs.getString(KEY_LC_STATE, CredentialLifecycleState.ACTIVE.name)
            ?: CredentialLifecycleState.ACTIVE.name
        val state = runCatching { CredentialLifecycleState.valueOf(stateName) }
            .getOrDefault(CredentialLifecycleState.ACTIVE)
        val settings = _settingsFlow.value
        val fallbackAlias = settings.clientKeyPem.removePrefix("keystore:")
            .takeIf { settings.clientKeyPem.startsWith("keystore:") } ?: ""
        return CredentialLifecycleRecord(
            state = state,
            activeAlias = prefs.getString(KEY_LC_ACTIVE_ALIAS, fallbackAlias) ?: fallbackAlias,
            stagingAlias = prefs.getString(KEY_LC_STAGING_ALIAS, "") ?: "",
            retiringAlias = prefs.getString(KEY_LC_RETIRING_ALIAS, "") ?: "",
            pendingCsrPem = prefs.getString(KEY_LC_PENDING_CSR, "") ?: "",
            candidateCertPem = prefs.getString(KEY_LC_CANDIDATE_CERT, "") ?: "",
            nextEligibleRenewalEpochSeconds = prefs.getLong(KEY_LC_NEXT_RENEWAL_EPOCH, 0L),
            consecutiveFailures = prefs.getInt(KEY_LC_FAILURES, 0)
        )
    }

    override fun commitSettingsAndLifecycle(
        settings: ClusterSettings,
        record: CredentialLifecycleRecord
    ): Boolean {
        val committed = prefs.edit()
            .putString(KEY_WIFI_HOST, settings.wifiHost)
            .putInt(KEY_WIFI_PORT, settings.wifiPort)
            .putString(KEY_CELLULAR_HOST, settings.cellularHost)
            .putInt(KEY_CELLULAR_PORT, settings.cellularPort)
            .putString(KEY_CA_CERT, settings.caCertPem)
            .putString(KEY_CLIENT_CERT, settings.clientCertPem)
            .putString(KEY_CLIENT_KEY, settings.clientKeyPem)
            .putString(KEY_LC_STATE, record.state.name)
            .putString(KEY_LC_ACTIVE_ALIAS, record.activeAlias)
            .putString(KEY_LC_STAGING_ALIAS, record.stagingAlias)
            .putString(KEY_LC_RETIRING_ALIAS, record.retiringAlias)
            .putString(KEY_LC_PENDING_CSR, record.pendingCsrPem)
            .putString(KEY_LC_CANDIDATE_CERT, record.candidateCertPem)
            .putLong(KEY_LC_NEXT_RENEWAL_EPOCH, record.nextEligibleRenewalEpochSeconds)
            .putInt(KEY_LC_FAILURES, record.consecutiveFailures)
            .commit()
        if (committed) {
            _settingsFlow.value = settings
        }
        return committed
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
        private const val KEY_LC_STATE: String = "cred_lc_state"
        private const val KEY_LC_ACTIVE_ALIAS: String = "cred_lc_active_alias"
        private const val KEY_LC_STAGING_ALIAS: String = "cred_lc_staging_alias"
        private const val KEY_LC_RETIRING_ALIAS: String = "cred_lc_retiring_alias"
        private const val KEY_LC_PENDING_CSR: String = "cred_lc_pending_csr"
        private const val KEY_LC_CANDIDATE_CERT: String = "cred_lc_candidate_cert"
        private const val KEY_LC_NEXT_RENEWAL_EPOCH: String = "cred_lc_next_renewal_epoch"
        private const val KEY_LC_FAILURES: String = "cred_lc_failures"

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
    private var lifecycleRecord = CredentialLifecycleRecord()

    override fun getSettings(): ClusterSettings = _settingsFlow.value

    override fun getLifecycleRecord(): CredentialLifecycleRecord = lifecycleRecord

    override fun commitSettingsAndLifecycle(
        settings: ClusterSettings,
        record: CredentialLifecycleRecord
    ): Boolean {
        lifecycleRecord = record
        _settingsFlow.value = settings
        return true
    }

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
