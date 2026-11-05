package com.androgrid.worker.network

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [DefaultMtlsSocketFactoryProvider].
 * Verifies TLS 1.3 mTLS socket factory and trust manager construction, credential parsing, and inspection.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
class MtlsSocketFactoryProviderTest {

    private val provider: MtlsSocketFactoryProvider = DefaultMtlsSocketFactoryProvider

    @Test
    fun testCreateMtlsContextFromPem_success() {
        val context = provider.createMtlsContextFromPem(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
            clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
        )

        assertThat(context).isNotNull()
        assertThat(context.socketFactory).isNotNull()
        assertThat(context.trustManager).isNotNull()
        assertThat(context.trustManager.acceptedIssuers).isNotEmpty()
    }

    @Test
    fun testCreateMtlsContextFromPkcs12_success() {
        val context = provider.createMtlsContextFromPkcs12(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            p12Bytes = TestCertificateFixtures.clientPkcs12Bytes,
            password = TestCertificateFixtures.CLIENT_PKCS12_PASSWORD.toCharArray()
        )

        assertThat(context).isNotNull()
        assertThat(context.socketFactory).isNotNull()
        assertThat(context.trustManager).isNotNull()
    }

    @Test
    fun testCreateMtlsContextFromPem_rejectsEmptyCaCert() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.createMtlsContextFromPem(
                caCertPem = "",
                clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
                clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
            )
        }
    }

    @Test
    fun testCreateMtlsContextFromPem_rejectsCorruptPrivateKey() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.createMtlsContextFromPem(
                caCertPem = TestCertificateFixtures.CA_CERT_PEM,
                clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
                clientKeyPem = "-----BEGIN PRIVATE KEY-----\nNOT_BASE_64_GARBAGE!!!\n-----END PRIVATE KEY-----"
            )
        }
    }

    @Test
    fun testInspectCertificate_validPem() {
        val info = provider.inspectCertificate(TestCertificateFixtures.CA_CERT_PEM)

        assertThat(info.subject).contains("AndroGrid Root CA")
        assertThat(info.issuer).contains("AndroGrid Root CA")
        assertThat(info.isValidNow).isTrue()
        assertThat(info.notAfter).isNotNull()
    }

    @Test
    fun testInspectCertificate_rejectsEmptyPem() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.inspectCertificate("")
        }
    }

    @Test
    fun testBuildSocketFactory_withValidCredentials_returnsContext() {
        val settings = ClusterSettings(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
            clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
        )

        val context = provider.buildSocketFactory(settings)
        assertThat(context).isNotNull()
        assertThat(context?.socketFactory).isNotNull()
    }

    @Test
    fun testBuildSocketFactory_withoutCredentials_returnsNull() {
        val settings = ClusterSettings() // Empty PEMs
        val context = provider.buildSocketFactory(settings)
        assertThat(context).isNull()
    }
}
