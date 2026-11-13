package com.androgrid.worker.enrollment

import com.androgrid.worker.network.MtlsContext
import com.androgrid.worker.network.MtlsTls13SocketFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Contract governing cryptographic key generation, on-device CSR construction,
 * and mTLS KeyStore credential persistence.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
interface KeyEnrollmentProvider {

    /**
     * Generates a new NIST P-256 (secp256r1) ECDSA key pair under the specified alias.
     *
     * @param alias Identifier alias for the key pair in the underlying KeyStore.
     * @return Generated [KeyPair].
     */
    fun generateKeyPair(alias: String): KeyPair

    /**
     * Constructs a PKCS#10 Certificate Signing Request (CSR) signed by the private key for alias.
     * The private key remains protected within the KeyStore boundary.
     *
     * @param alias Identifier alias for the key pair in the KeyStore.
     * @param commonName Subject Common Name (CN), representing the worker node identifier.
     * @return PEM-encoded PKCS#10 CSR string.
     */
    fun generateCsrPem(alias: String, commonName: String): String

    /**
     * Installs the signed X.509 client certificate and root CA certificate into the KeyStore.
     *
     * @param alias Identifier alias for the key pair in the KeyStore.
     * @param clientCertPem PEM-encoded issued client certificate chain.
     * @param caCertPem PEM-encoded Root CA certificate.
     */
    fun installCertificateChain(alias: String, clientCertPem: String, caCertPem: String)

    /**
     * Queries whether a complete private key and certificate chain exist under alias.
     *
     * @param alias Identifier alias for the key pair in the KeyStore.
     * @return True if enrolled and ready for mTLS, false otherwise.
     */
    fun isEnrolled(alias: String): Boolean

    /**
     * Constructs an [MtlsContext] configured with the client certificate chain and Root CA trust anchor.
     *
     * @param alias Identifier alias for the enrolled client key pair.
     * @param caCertPem PEM-encoded Root CA certificate for server verification.
     * @return Configured [MtlsContext] encapsulating SSLSocketFactory and X509TrustManager.
     */
    fun createMtlsContext(alias: String, caCertPem: String): MtlsContext

    /**
     * Deletes the key pair and certificate chain under alias from the KeyStore.
     *
     * @param alias Identifier alias for the key pair to purge.
     */
    fun deleteKey(alias: String)
}

/**
 * Software-backed [KeyEnrollmentProvider] implementation using standard JCA and in-memory KeyStore.
 * Designed for hermetic testing on host JVM and environments lacking AndroidKeyStore hardware daemon.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class SoftwareKeyEnrollmentProvider : KeyEnrollmentProvider {

    private val keyPairs = mutableMapOf<String, KeyPair>()
    private val certChains = mutableMapOf<String, Array<Certificate>>()

    override fun generateKeyPair(alias: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        kpg.initialize(ecSpec, SecureRandom())
        val pair = kpg.generateKeyPair()
        keyPairs[alias] = pair
        certChains.remove(alias)
        return pair
    }

    override fun generateCsrPem(alias: String, commonName: String): String {
        val pair = keyPairs[alias] ?: throw IllegalStateException("No key pair found for alias: $alias")
        return KeyEnrollmentUtils.buildCsrPem(pair.public, pair.private, commonName)
    }

    override fun installCertificateChain(alias: String, clientCertPem: String, caCertPem: String) {
        val clientCerts = KeyEnrollmentUtils.parseCertificateChain(clientCertPem)
        require(clientCerts.isNotEmpty()) { "Client certificate chain is empty" }
        certChains[alias] = clientCerts.toTypedArray()
    }

    override fun isEnrolled(alias: String): Boolean {
        return keyPairs.containsKey(alias) && certChains.containsKey(alias)
    }

    override fun createMtlsContext(alias: String, caCertPem: String): MtlsContext {
        val pair = keyPairs[alias] ?: throw IllegalStateException("No key pair found for alias: $alias")
        val chain = certChains[alias] ?: throw IllegalStateException("No certificate chain installed for alias: $alias")

        // 1. Build TrustStore with CA cert
        val cf = CertificateFactory.getInstance("X.509")
        val caCert = cf.generateCertificate(ByteArrayInputStream(caCertPem.toByteArray(Charsets.UTF_8)))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca_root", caCert)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        // 2. Build KeyStore with client key and chain
        val clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(alias, pair.private, charArrayOf(), chain)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientKeyStore, charArrayOf())
        }

        val sslContext = MtlsContext.createSslContext().apply {
            init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
        }

        return MtlsContext(
            socketFactory = MtlsTls13SocketFactory(sslContext.socketFactory),
            trustManager = trustManager
        )
    }

    override fun deleteKey(alias: String) {
        keyPairs.remove(alias)
        certChains.remove(alias)
    }

    /**
     * Testing helper generating a self-signed X.509 certificate for the generated key pair.
     *
     * @param alias KeyStore entry alias.
     * @param commonName Subject Common Name.
     * @return PEM-encoded self-signed certificate.
     */
    fun createSelfSignedCertPemForTesting(alias: String, commonName: String): String {
        val pair = keyPairs[alias] ?: throw IllegalStateException("No key pair found for alias: $alias")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000)
        val serialNumber = BigInteger.valueOf(now)

        val x500Name = X500Name("CN=$commonName")
        val certBuilder = JcaX509v3CertificateBuilder(
            x500Name,
            serialNumber,
            notBefore,
            notAfter,
            x500Name,
            pair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(cert) }
        return writer.toString()
    }
}
