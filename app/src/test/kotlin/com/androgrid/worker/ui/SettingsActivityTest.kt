package com.androgrid.worker.ui

import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.TestCertificateFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

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

    @Test
    fun testSettingsActivity_handleP12Import_validAndInvalidBundles() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo
        activity.ioDispatcher = Dispatchers.Main.immediate

        controller.create().start().resume()

        // Corrupt file surfaces persistent inline error banner instead of silent failure
        val badFile = java.io.File.createTempFile("bad-", ".p12", activity.cacheDir).apply {
            writeBytes("not-valid-pkcs12".toByteArray())
        }
        runBlocking { activity.handleP12Import(android.net.Uri.fromFile(badFile)).join() }
        ShadowLooper.idleMainLooper()
        assertThat(activity.p12StatusBanner.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(activity.p12StatusBanner.text.toString()).contains("Failed to parse PKCS#12")

        // Valid PKCS#12 populates clientCertInput and clientKeyInput specifically
        val goodFile = createTestP12File(activity.cacheDir, charArrayOf())
        runBlocking { activity.handleP12Import(android.net.Uri.fromFile(goodFile)).join() }
        ShadowLooper.idleMainLooper()
        assertThat(activity.p12StatusBanner.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(activity.p12StatusBanner.text.toString()).contains("tap Save Settings to apply")
        assertThat(activity.clientCertInput.text.toString()).contains("-----BEGIN CERTIFICATE-----")
        assertThat(activity.clientKeyInput.text.toString()).contains("-----BEGIN PRIVATE KEY-----")

        badFile.delete()
        goodFile.delete()
        controller.destroy()
    }

    @Test
    fun testSettingsActivity_handleP12Import_passwordDialog_emptyAndRetryFlow() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo
        activity.ioDispatcher = Dispatchers.Main.immediate

        controller.create().start().resume()

        val pass = "my-p12-secret".toCharArray()
        val protectedFile = createTestP12File(activity.cacheDir, pass)
        val uri = android.net.Uri.fromFile(protectedFile)

        // Initial import without password triggers passphrase dialog
        runBlocking { activity.handleP12Import(uri).join() }
        ShadowLooper.idleMainLooper()
        val dialog1 = ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog
        assertThat(dialog1).isNotNull()

        // Submitting empty passphrase shows error and re-prompts instead of looping into handleP12Import
        dialog1!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertThat(activity.p12StatusBanner.text.toString()).contains("Passphrase cannot be empty")
        val dialog2 = ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog
        assertThat(dialog2).isNotNull()

        // Entering correct passphrase populates clientCertInput and clientKeyInput
        val passEdit = getAllViews(dialog2!!.window!!.decorView).filterIsInstance<EditText>().first()
        passEdit.setText("my-p12-secret")
        dialog2.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertThat(activity.clientCertInput.text.toString()).contains("-----BEGIN CERTIFICATE-----")
        assertThat(activity.clientKeyInput.text.toString()).contains("-----BEGIN PRIVATE KEY-----")
        assertThat(activity.p12StatusBanner.text.toString()).contains("tap Save Settings to apply")

        protectedFile.delete()
        controller.destroy()
    }

    @Test
    fun testSettingsActivity_handleP12Import_dialogCancelUpdatesBanner() {
        val repo = InMemoryClusterSettingsRepository()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.repository = repo
        activity.ioDispatcher = Dispatchers.Main.immediate

        controller.create().start().resume()

        val protectedFile = createTestP12File(activity.cacheDir, "secret".toCharArray())
        runBlocking { activity.handleP12Import(android.net.Uri.fromFile(protectedFile)).join() }
        ShadowLooper.idleMainLooper()

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog
        assertThat(dialog).isNotNull()
        dialog!!.cancel()
        ShadowLooper.idleMainLooper()

        assertThat(activity.p12StatusBanner.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(activity.p12StatusBanner.text.toString()).contains("cancelled")

        protectedFile.delete()
        controller.destroy()
    }

    private fun createTestP12File(dir: java.io.File, password: CharArray): java.io.File {
        val kpg = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=p12-ui-test"),
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 60_000),
            java.util.Date(now + 86_400_000),
            org.bouncycastle.asn1.x500.X500Name("CN=p12-ui-test"),
            kp.public
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(kp.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        val ks = java.security.KeyStore.getInstance(
            "PKCS12",
            org.bouncycastle.jce.provider.BouncyCastleProvider()
        ).apply {
            load(null, password)
            setKeyEntry("worker", kp.private, password, arrayOf(cert))
        }
        return java.io.File.createTempFile("test-", ".p12", dir).apply {
            outputStream().use { ks.store(it, password) }
        }
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
