package com.androgrid.worker.ui

import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.TestCertificateFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric UI tests for [SettingsActivity].
 * Verifies operator configuration input, mTLS certificate validation banner, and persistence.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001], [REQ-OP-003]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    @Test
    fun testSettingsActivity_loadsInitialConfiguration() {
        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(
                wifiHost = "10.1.1.200",
                wifiPort = 50060,
                cellularHost = "edge.androgrid.org",
                cellularPort = 8443
            )
        )

        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo

        controller.create().start().resume()

        val wifiHostField = findEditTextByHint(activity, "192.168.1.100")
        assertThat(wifiHostField?.text?.toString()).isEqualTo("10.1.1.200")

        val wifiPortField = findEditTextByHint(activity, "50051")
        assertThat(wifiPortField?.text?.toString()).isEqualTo("50060")

        controller.destroy()
    }

    @Test
    fun testSettingsActivity_savePersistsNewValues() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo

        controller.create().start().resume()

        val wifiHostField = findEditTextByHint(activity, "192.168.1.100")
        wifiHostField?.setText("192.168.88.1")

        val saveButton = findButtonByText(activity, "Save Settings")
        saveButton?.performClick()

        assertThat(repo.getSettings().wifiHost).isEqualTo("192.168.88.1")
        controller.destroy()
    }

    @Test
    fun testSettingsActivity_displaysValidMtlsBanner() {
        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(
                caCertPem = TestCertificateFixtures.CA_CERT_PEM,
                clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
                clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
            )
        )

        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo

        controller.create().start().resume()

        val banner = findValidationBanner(activity)
        assertThat(banner?.text?.toString()).contains("VALID")
        assertThat(banner?.text?.toString()).contains("Private Key: [LOADED]")

        controller.destroy()
    }

    @Test
    fun testSettingsActivity_containsScanQrButton() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo

        controller.create().start().resume()

        val scanButton = findButtonByText(activity, "Scan Enrollment QR Code")
        assertThat(scanButton).isNotNull()
        controller.destroy()
    }

    @Test
    fun testSettingsActivity_handleScannedQrPayload_invalidJson_doesNotCrash() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo

        controller.create().start().resume()

        activity.handleScannedQrPayload("invalid-non-json-content")
        assertThat(repo.getSettings().hasMtlsCredentials).isFalse()

        controller.destroy()
    }

    private fun findEditTextByHint(activity: SettingsActivity, hint: String): EditText? {
        val allViews = getAllViews(activity.window.decorView)
        return allViews.filterIsInstance<EditText>().find { it.hint == hint }
    }

    private fun findButtonByText(activity: SettingsActivity, text: String): Button? {
        val allViews = getAllViews(activity.window.decorView)
        return allViews.filterIsInstance<Button>().find { it.text == text }
    }

    private fun findValidationBanner(activity: SettingsActivity): TextView? {
        val allViews = getAllViews(activity.window.decorView)
        return allViews.filterIsInstance<TextView>().find {
            it.text?.contains("Root CA:") == true || it.text?.contains("Private Key:") == true
        }
    }

    private fun getAllViews(root: android.view.View): List<android.view.View> {
        val result = mutableListOf<android.view.View>()
        result.add(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(getAllViews(root.getChildAt(i)))
            }
        }
        return result
    }
}
