package com.androgrid.worker.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit and Robolectric tests for [ClusterSettingsRepository] implementations.
 * Verifies reactive state flow emission and persistent storage across app restarts.
 *
 * Traces to: [INV-001], [INV-002], [REQ-OP-001], [REQ-OP-003], [NFR-SEC-001]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(
            SharedPreferencesClusterSettingsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
    }

    @Test
    fun testInMemoryRepository_defaultValues() {
        val repo = InMemoryClusterSettingsRepository()
        val settings = repo.getSettings()

        assertThat(settings.wifiHost).isEqualTo("192.168.1.100")
        assertThat(settings.wifiPort).isEqualTo(50051)
        assertThat(settings.cellularHost).isEqualTo("grpc.cluster.domain.com")
        assertThat(settings.cellularPort).isEqualTo(443)
        assertThat(settings.hasMtlsCredentials).isFalse()
    }

    @Test
    fun testInMemoryRepository_updateEndpointsAndFlow() {
        val repo = InMemoryClusterSettingsRepository()

        repo.updateWifiEndpoint("10.0.0.50", 50052)
        assertThat(repo.getSettings().wifiHost).isEqualTo("10.0.0.50")
        assertThat(repo.getSettings().wifiPort).isEqualTo(50052)
        assertThat(repo.settingsFlow.value.wifiHost).isEqualTo("10.0.0.50")

        repo.updateCellularEndpoint("wan.androgrid.net", 8443)
        assertThat(repo.getSettings().cellularHost).isEqualTo("wan.androgrid.net")
        assertThat(repo.getSettings().cellularPort).isEqualTo(8443)
    }

    @Test
    fun testInMemoryRepository_certificatesLifecycle() {
        val repo = InMemoryClusterSettingsRepository()

        repo.updateCertificates("CA_PEM", "CLIENT_PEM", "KEY_PEM")
        assertThat(repo.getSettings().hasMtlsCredentials).isTrue()

        repo.clearCertificates()
        assertThat(repo.getSettings().hasMtlsCredentials).isFalse()
        assertThat(repo.getSettings().caCertPem).isEmpty()
    }

    @Test
    fun testSharedPreferencesRepository_persistenceAcrossInstances() {
        val repo1 = SharedPreferencesClusterSettingsRepository(context)
        repo1.updateWifiEndpoint("172.16.0.10", 9090)
        repo1.updateCertificates("CA_CERT", "CLIENT_CERT", "KEY_DATA")

        // Re-instantiate repository pointing to same underlying Context
        val repo2 = SharedPreferencesClusterSettingsRepository(context)
        val loaded = repo2.getSettings()

        assertThat(loaded.wifiHost).isEqualTo("172.16.0.10")
        assertThat(loaded.wifiPort).isEqualTo(9090)
        assertThat(loaded.caCertPem).isEqualTo("CA_CERT")
        assertThat(loaded.clientCertPem).isEqualTo("CLIENT_CERT")
        assertThat(loaded.clientKeyPem).isEqualTo("KEY_DATA")
    }

    @Test
    fun testSharedPreferencesRepository_resetDefaults() {
        val repo = SharedPreferencesClusterSettingsRepository(context)
        repo.updateWifiEndpoint("1.2.3.4", 1234)
        repo.resetDefaults()

        val settings = repo.getSettings()
        assertThat(settings.wifiHost).isEqualTo(ClusterSettings.DEFAULT_WIFI_HOST)
        assertThat(settings.wifiPort).isEqualTo(ClusterSettings.DEFAULT_WIFI_PORT)
        assertThat(settings.caCertPem).isEmpty()
    }
}
