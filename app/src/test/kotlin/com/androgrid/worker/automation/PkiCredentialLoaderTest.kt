package com.androgrid.worker.automation

import android.content.Context
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.TestCertificateFixtures
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

/**
 * Unit and Robolectric tests for [PkiCredentialLoader].
 * Verifies discovery and ingestion of Mutual TLS certificates from file storage.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PkiCredentialLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repo: InMemoryClusterSettingsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repo = InMemoryClusterSettingsRepository()
    }

    @Test
    fun testLoadFromDirectory_withPemFiles_populatesCertificates() {
        val pkiDir = tempFolder.newFolder("pki")
        File(pkiDir, "ca.crt").writeText(TestCertificateFixtures.CA_CERT_PEM)
        File(pkiDir, "client.crt").writeText(TestCertificateFixtures.CLIENT_CERT_PEM)
        File(pkiDir, "client.key").writeText(TestCertificateFixtures.clientPrivateKeyPem)

        val success = PkiCredentialLoader.loadFromDirectory(pkiDir, repo)
        assertThat(success).isTrue()

        val settings = repo.getSettings()
        assertThat(settings.hasMtlsCredentials).isTrue()
        assertThat(settings.caCertPem).isEqualTo(TestCertificateFixtures.CA_CERT_PEM)
        assertThat(settings.clientCertPem).isEqualTo(TestCertificateFixtures.CLIENT_CERT_PEM)
        assertThat(settings.clientKeyPem).isEqualTo(TestCertificateFixtures.clientPrivateKeyPem)
    }

    @Test
    fun testLoadFromDirectory_missingFiles_handlesGracefully() {
        val emptyDir = tempFolder.newFolder("empty_pki")
        val success = PkiCredentialLoader.loadFromDirectory(emptyDir, repo)
        assertThat(success).isFalse()
        assertThat(repo.getSettings().hasMtlsCredentials).isFalse()
    }

    @Test
    fun testLoadFromDirectory_nonExistentDirectory_returnsFalse() {
        val nonExistentDir = File(tempFolder.root, "does_not_exist")
        val success = PkiCredentialLoader.loadFromDirectory(nonExistentDir, repo)
        assertThat(success).isFalse()
    }

    @Test
    fun testLoadFromExplicitPaths_populatesCertificates() {
        val caFile = tempFolder.newFile("custom_ca.pem").apply { writeText(TestCertificateFixtures.CA_CERT_PEM) }
        val certFile = tempFolder.newFile("custom_client.pem").apply { writeText(TestCertificateFixtures.CLIENT_CERT_PEM) }
        val keyFile = tempFolder.newFile("custom_key.pem").apply { writeText(TestCertificateFixtures.clientPrivateKeyPem) }

        val success = PkiCredentialLoader.loadFromPaths(
            caPath = caFile.absolutePath,
            certPath = certFile.absolutePath,
            keyPath = keyFile.absolutePath,
            repository = repo
        )
        assertThat(success).isTrue()
        assertThat(repo.getSettings().hasMtlsCredentials).isTrue()
    }

    @Test
    fun testGetCanonicalPkiDirectory_resolvesUnderFilesDir() {
        val canonicalDir = PkiCredentialLoader.getCanonicalPkiDirectory(context)
        assertThat(canonicalDir.absolutePath).endsWith(File.separator + "pki")
        assertThat(canonicalDir.parentFile).isEqualTo(context.filesDir)
    }
}
