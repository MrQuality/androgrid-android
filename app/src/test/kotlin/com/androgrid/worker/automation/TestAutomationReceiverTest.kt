package com.androgrid.worker.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.TestCertificateFixtures
import com.androgrid.worker.service.ServiceController
import com.androgrid.worker.service.WorkScheduler
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit and Robolectric tests for [TestAutomationReceiver].
 * Validates headless configuration, participation lifecycle intents, and debug safety guards.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001], [REQ-OP-002]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestAutomationReceiverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repo: InMemoryClusterSettingsRepository
    private lateinit var serviceController: FakeServiceController
    private lateinit var workScheduler: FakeWorkScheduler
    private lateinit var receiver: TestAutomationReceiver

    private class FakeServiceController : ServiceController {
        val started = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        override fun startWifiSyncService() { started.set(true); stopped.set(false) }
        override fun stopWifiSyncService() { stopped.set(true); started.set(false) }
    }

    private class FakeWorkScheduler : WorkScheduler {
        val scheduled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        override fun scheduleCellularSpotWork() { scheduled.set(true); cancelled.set(false) }
        override fun cancelCellularSpotWork() { cancelled.set(true); scheduled.set(false) }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Ensure application is flagged as debuggable in test environment
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE

        repo = InMemoryClusterSettingsRepository()
        serviceController = FakeServiceController()
        workScheduler = FakeWorkScheduler()
        receiver = TestAutomationReceiver(
            settingsRepositoryProvider = { repo },
            serviceControllerProvider = { serviceController },
            workSchedulerProvider = { workScheduler }
        )
    }

    @Test
    fun testConfigureEndpointIntent_updatesRepositorySettings() {
        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "10.0.2.2")
            putExtra(TestAutomationReceiver.EXTRA_WIFI_PORT, 50051)
            putExtra(TestAutomationReceiver.EXTRA_CELLULAR_HOST, "wan.androgrid.net")
            putExtra(TestAutomationReceiver.EXTRA_CELLULAR_PORT, 8443)
        }

        receiver.onReceive(context, intent)

        val settings = repo.getSettings()
        assertThat(settings.wifiHost).isEqualTo("10.0.2.2")
        assertThat(settings.wifiPort).isEqualTo(50051)
        assertThat(settings.cellularHost).isEqualTo("wan.androgrid.net")
        assertThat(settings.cellularPort).isEqualTo(8443)
    }

    @Test
    fun testConfigureEndpointIntent_withPkiDir_loadsCertificates() {
        val pkiDir = tempFolder.newFolder("pki_test")
        File(pkiDir, "ca.crt").writeText(TestCertificateFixtures.CA_CERT_PEM)
        File(pkiDir, "client.crt").writeText(TestCertificateFixtures.CLIENT_CERT_PEM)
        File(pkiDir, "client.key").writeText(TestCertificateFixtures.clientPrivateKeyPem)

        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "192.168.1.50")
            putExtra(TestAutomationReceiver.EXTRA_PKI_DIR, pkiDir.absolutePath)
        }

        receiver.onReceive(context, intent)

        val settings = repo.getSettings()
        assertThat(settings.wifiHost).isEqualTo("192.168.1.50")
        assertThat(settings.hasMtlsCredentials).isTrue()
    }

    @Test
    fun testConfigureEndpointIntent_withRawPemExtras_populatesCertificates() {
        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "127.0.0.1")
            putExtra(TestAutomationReceiver.EXTRA_CA_CERT_PEM, TestCertificateFixtures.CA_CERT_PEM)
            putExtra(TestAutomationReceiver.EXTRA_CLIENT_CERT_PEM, TestCertificateFixtures.CLIENT_CERT_PEM)
            putExtra(TestAutomationReceiver.EXTRA_CLIENT_KEY_PEM, TestCertificateFixtures.clientPrivateKeyPem)
        }

        receiver.onReceive(context, intent)

        val settings = repo.getSettings()
        assertThat(settings.hasMtlsCredentials).isTrue()
        assertThat(settings.clientCertPem).isEqualTo(TestCertificateFixtures.CLIENT_CERT_PEM)
    }

    @Test
    fun testConfigureEndpointIntent_withAutoStart_engagesService() {
        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "10.0.2.2")
            putExtra(TestAutomationReceiver.EXTRA_AUTO_START, true)
        }

        receiver.onReceive(context, intent)

        assertThat(serviceController.started.get()).isTrue()
    }

    @Test
    fun testStartParticipationIntent_triggersWifiSyncService() {
        val intent = Intent(TestAutomationReceiver.ACTION_START_PARTICIPATION)
        receiver.onReceive(context, intent)

        assertThat(serviceController.started.get()).isTrue()
    }

    @Test
    fun testStopParticipationIntent_stopsWifiSyncAndSpotWork() {
        val intent = Intent(TestAutomationReceiver.ACTION_STOP_PARTICIPATION)
        receiver.onReceive(context, intent)

        assertThat(serviceController.stopped.get()).isTrue()
        assertThat(workScheduler.cancelled.get()).isTrue()
    }

    @Test
    fun testNonDebuggableApplication_rejectsAutomationIntent() {
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()

        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "malicious.host.net")
        }

        receiver.onReceive(context, intent)

        assertThat(repo.getSettings().wifiHost).isEqualTo(ClusterSettings.DEFAULT_WIFI_HOST)
    }
}
