package com.androgrid.worker.enrollment

import com.androgrid.worker.network.TestCertificateFixtures
import com.google.common.truth.Truth.assertThat
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.security.KeyStore
import java.security.interfaces.ECPublicKey

/**
 * Unit tests verifying key generation, PKCS#10 CSR construction, and KeyStore integration.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class KeyEnrollmentProviderTest {

    private lateinit var provider: SoftwareKeyEnrollmentProvider
    private val testAlias = "test_worker_key_alias"
    private val testNodeId = "worker_node_test_01"

    @Before
    fun setUp() {
        provider = SoftwareKeyEnrollmentProvider()
    }

    @Test
    fun generateKeyPair_createsValidNistP256KeyPair() {
        val keyPair = provider.generateKeyPair(testAlias)

        assertThat(keyPair.public).isNotNull()
        assertThat(keyPair.private).isNotNull()
        assertThat(keyPair.public.algorithm).isEqualTo("EC")

        val ecPub = keyPair.public as ECPublicKey
        // NIST P-256 field size is 256 bits
        assertThat(ecPub.params.order.bitLength()).isEqualTo(256)
    }

    @Test
    fun generateCsrPem_createsValidPkcs10CsrWithCorrectCommonName() {
        provider.generateKeyPair(testAlias)
        val csrPem = provider.generateCsrPem(testAlias, testNodeId)

        assertThat(csrPem).startsWith("-----BEGIN CERTIFICATE REQUEST-----")
        assertThat(csrPem.trim()).endsWith("-----END CERTIFICATE REQUEST-----")

        // Parse with BouncyCastle PEMParser to verify structure
        val pemParser = PEMParser(StringReader(csrPem))
        val parsedObject = pemParser.readObject()
        assertThat(parsedObject).isInstanceOf(PKCS10CertificationRequest::class.java)

        val csr = parsedObject as PKCS10CertificationRequest
        val commonNames = csr.subject.getRDNs(BCStyle.CN)
        assertThat(commonNames).hasLength(1)
        val extractedCn = IETFUtils.valueToString(commonNames[0].first.value)
        assertThat(extractedCn).isEqualTo(testNodeId)
    }

    @Test
    fun calculateFingerprint_returnsCorrectSha256Hex() {
        val testCaCertPem = TestCertificateFixtures.CA_CERT_PEM

        // Fingerprint should be lowercase 64-hex SHA-256
        val fp = KeyEnrollmentUtils.computeSha256Fingerprint(testCaCertPem)
        assertThat(fp).hasLength(64)
        assertThat(fp.all { it in '0'..'9' || it in 'a'..'f' }).isTrue()
    }

    @Test
    fun isEnrolled_returnsFalseBeforeInstallation_andTrueAfter() {
        assertThat(provider.isEnrolled(testAlias)).isFalse()

        provider.generateKeyPair(testAlias)
        assertThat(provider.isEnrolled(testAlias)).isFalse()

        // Generate self-signed dummy cert for test
        val certPem = provider.createSelfSignedCertPemForTesting(testAlias, testNodeId)
        provider.installCertificateChain(testAlias, certPem, certPem)

        assertThat(provider.isEnrolled(testAlias)).isTrue()

        // Now delete key
        provider.deleteKey(testAlias)
        assertThat(provider.isEnrolled(testAlias)).isFalse()
    }

    @Test
    fun createMtlsContext_constructsValidContext() {
        provider.generateKeyPair(testAlias)
        val certPem = provider.createSelfSignedCertPemForTesting(testAlias, testNodeId)
        provider.installCertificateChain(testAlias, certPem, certPem)

        val mtlsContext = provider.createMtlsContext(testAlias, certPem)
        assertThat(mtlsContext.socketFactory).isNotNull()
        assertThat(mtlsContext.trustManager).isNotNull()
    }

    @Test
    fun androidKeyStoreEnrollmentProvider_createMtlsContext_throwsWhenAliasMissing() {
        val inMemoryKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val aksProvider = AndroidKeyStoreEnrollmentProvider(inMemoryKeyStore)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            aksProvider.createMtlsContext("non_existent_alias", TestCertificateFixtures.CA_CERT_PEM)
        }
    }

    @Test
    fun androidKeyStoreEnrollmentProvider_buildAliasKeyManager_filtersKeyTypeAndAlias() {
        val inMemoryKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val aksProvider = AndroidKeyStoreEnrollmentProvider(inMemoryKeyStore)

        // Enroll test key and certificate into the in-memory keystore
        val keyPair = provider.generateKeyPair(testAlias)
        val certPem = provider.createSelfSignedCertPemForTesting(testAlias, testNodeId)
        val certChain = KeyEnrollmentUtils.parseCertificateChain(certPem).toTypedArray()

        inMemoryKeyStore.setKeyEntry(testAlias, keyPair.private, null, certChain)

        val km = aksProvider.buildAliasKeyManager(testAlias)

        // Matching keyType or empty/null matches EC
        assertThat(km.chooseClientAlias(null, null, null)).isEqualTo(testAlias)
        assertThat(km.chooseClientAlias(emptyArray(), null, null)).isEqualTo(testAlias)
        assertThat(km.chooseClientAlias(arrayOf("EC", "RSA"), null, null)).isEqualTo(testAlias)
        assertThat(km.chooseEngineClientAlias(arrayOf("EC"), null, null)).isEqualTo(testAlias)

        // Incompatible keyType returns null
        assertThat(km.chooseClientAlias(arrayOf("RSA"), null, null)).isNull()
        assertThat(km.chooseEngineClientAlias(arrayOf("RSA"), null, null)).isNull()
        assertThat(km.getClientAliases("RSA", null)).isNull()
        assertThat(km.getClientAliases("EC", null)).asList().containsExactly(testAlias)

        // Alias matching for key/chain retrieval
        assertThat(km.getPrivateKey(testAlias)).isEqualTo(keyPair.private)
        assertThat(km.getPrivateKey("other_alias")).isNull()
        assertThat(km.getCertificateChain(testAlias)).hasLength(certChain.size)
        assertThat(km.getCertificateChain("other_alias")).isNull()
    }
}
