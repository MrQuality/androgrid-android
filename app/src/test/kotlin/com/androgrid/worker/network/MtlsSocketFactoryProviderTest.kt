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

    @Test
    fun testMtlsTls13SocketFactory_enforcesStrictTls13() {
        val context = provider.createMtlsContextFromPem(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
            clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
        )
        val socket = context.socketFactory.createSocket() as javax.net.ssl.SSLSocket
        assertThat(socket.enabledProtocols.toList()).containsExactly("TLSv1.3")
        assertThat(socket.enabledProtocols.toList()).doesNotContain("TLSv1.2")
    }

    @Test
    fun testCreateMtlsContextFromPkcs12_openSsl3Pbes2Bundle_succeeds() {
        val kpg = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=pbes2-mtls-worker"),
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 60_000),
            java.util.Date(now + 86_400_000),
            org.bouncycastle.asn1.x500.X500Name("CN=pbes2-mtls-worker"),
            kp.public
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(kp.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        val pass = "pbes2-mtls-pass".toCharArray()
        val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()
        val keyBag = org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder(
            kp.private,
            org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder(
                org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_aes256_CBC
            ).setProvider(bc).build(pass)
        ).build()
        val certBag = org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder(cert).build()
        val p12Bytes = org.bouncycastle.pkcs.PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(
                org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder(
                    org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256
                ).setProvider(bc),
                pass
            ).encoded

        val context = provider.createMtlsContextFromPkcs12(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            p12Bytes = p12Bytes,
            password = pass
        )
        assertThat(context.socketFactory).isNotNull()
        assertThat(context.trustManager).isNotNull()
    }
}
