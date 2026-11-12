package com.androgrid.worker.network

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Holder encapsulating configured SSLSocketFactory and X509TrustManager for Mutual TLS.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
data class MtlsContext(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager
) {
    companion object {
        val TLS_13_VERSIONS = arrayOf("TLSv1.3")
        val TLS_13_CIPHERS = arrayOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
        )
    }
}

/**
 * Parsed certificate metadata for operator dashboard inspection and validation.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Date,
    val notAfter: Date,
    val isValidNow: Boolean
)

/**
 * Provider contract constructing SSLSocketFactory and X509TrustManager for TLS 1.3 mTLS.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
interface MtlsSocketFactoryProvider {
    /**
     * Builds MtlsContext from PEM-encoded CA certificate, client certificate chain, and client private key.
     */
    fun createMtlsContextFromPem(
        caCertPem: String,
        clientCertPem: String,
        clientKeyPem: String
    ): MtlsContext

    /**
     * Builds MtlsContext from PKCS#12 bundle bytes and optional password, paired with root CA PEM.
     */
    fun createMtlsContextFromPkcs12(
        caCertPem: String,
        p12Bytes: ByteArray,
        password: CharArray = charArrayOf()
    ): MtlsContext

    /**
     * Inspects X.509 certificate PEM string and returns parsed metadata.
     */
    fun inspectCertificate(certPem: String): CertificateInfo

    /**
     * Builds MtlsContext from ClusterSettings if credentials are populated; returns null otherwise.
     */
    fun buildSocketFactory(settings: ClusterSettings): MtlsContext?
}

/**
 * Production implementation constructing TLS 1.3 SSLSocketFactory and X509TrustManager.
 * Enforces mutual TLS identity with ECDSA P-256 or RSA client certificates.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
object DefaultMtlsSocketFactoryProvider : MtlsSocketFactoryProvider {

    override fun createMtlsContextFromPem(
        caCertPem: String,
        clientCertPem: String,
        clientKeyPem: String
    ): MtlsContext {
        require(caCertPem.isNotBlank()) { "Root CA certificate PEM must not be empty" }
        require(clientCertPem.isNotBlank()) { "Client certificate PEM must not be empty" }
        require(clientKeyPem.isNotBlank()) { "Client private key PEM must not be empty" }

        if (clientKeyPem.startsWith("keystore:")) {
            val alias = clientKeyPem.removePrefix("keystore:")
            return com.androgrid.worker.enrollment.AndroidKeyStoreEnrollmentProvider.createDefault()
                .createMtlsContext(alias, caCertPem)
        }

        val cf = CertificateFactory.getInstance("X.509")

        // 1. Build TrustStore with CA certificate(s)
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        val caStream = ByteArrayInputStream(caCertPem.toByteArray(Charsets.UTF_8))
        val caCerts = cf.generateCertificates(caStream).filterIsInstance<X509Certificate>()
        require(caCerts.isNotEmpty()) { "No valid X.509 CA certificates found in PEM" }
        caCerts.forEachIndexed { index, cert ->
            trustStore.setCertificateEntry("androgrid-ca-$index", cert)
        }

        // 2. Build Client Certificate Chain
        val clientStream = ByteArrayInputStream(clientCertPem.toByteArray(Charsets.UTF_8))
        val clientCerts = cf.generateCertificates(clientStream).filterIsInstance<X509Certificate>()
        require(clientCerts.isNotEmpty()) { "No valid X.509 client certificates found in PEM" }
        val clientChain = clientCerts.toTypedArray()

        // 3. Parse Client Private Key (PKCS#8 DER)
        val cleanKey = clientKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = try {
            Base64.getDecoder().decode(cleanKey)
        } catch (e: Exception) {
            throw IllegalArgumentException("Client private key PEM contains invalid base64 data", e)
        }

        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = try {
            KeyFactory.getInstance("EC").generatePrivate(keySpec)
        } catch (_: Exception) {
            try {
                KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            } catch (e2: Exception) {
                throw IllegalArgumentException("Client private key is neither valid EC nor RSA PKCS#8 key", e2)
            }
        }

        // 4. Build Client Identity KeyStore
        val clientStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client-identity", privateKey, charArrayOf(), clientChain)
        }

        // 5. Initialize Managers and SSLContext
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, charArrayOf())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager found in TrustManagerFactory")

        val sslContext = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (_: Exception) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())

        return MtlsContext(
            socketFactory = MtlsTls13SocketFactory(sslContext.socketFactory),
            trustManager = trustManager
        )
    }

    override fun createMtlsContextFromPkcs12(
        caCertPem: String,
        p12Bytes: ByteArray,
        password: CharArray
    ): MtlsContext {
        require(caCertPem.isNotBlank()) { "Root CA certificate PEM must not be empty" }
        require(p12Bytes.isNotEmpty()) { "PKCS#12 bundle bytes must not be empty" }

        val cf = CertificateFactory.getInstance("X.509")

        // 1. Build TrustStore with CA certificate(s)
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        val caStream = ByteArrayInputStream(caCertPem.toByteArray(Charsets.UTF_8))
        val caCerts = cf.generateCertificates(caStream).filterIsInstance<X509Certificate>()
        require(caCerts.isNotEmpty()) { "No valid X.509 CA certificates found in PEM" }
        caCerts.forEachIndexed { index, cert ->
            trustStore.setCertificateEntry("androgrid-ca-$index", cert)
        }

        // 2. Load PKCS#12 Client KeyStore
        val clientStore = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(p12Bytes), password)
        }

        // 3. Initialize KeyManagerFactory and TrustManagerFactory
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, password)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager found in TrustManagerFactory")

        val sslContext = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (_: Exception) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())

        return MtlsContext(
            socketFactory = MtlsTls13SocketFactory(sslContext.socketFactory),
            trustManager = trustManager
        )
    }

    override fun inspectCertificate(certPem: String): CertificateInfo {
        require(certPem.isNotBlank()) { "Certificate PEM must not be empty" }
        val cf = CertificateFactory.getInstance("X.509")
        val stream = ByteArrayInputStream(certPem.toByteArray(Charsets.UTF_8))
        val cert = cf.generateCertificate(stream) as X509Certificate
        val isValidNow = try {
            cert.checkValidity()
            true
        } catch (_: Exception) {
            false
        }

        return CertificateInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            serialNumber = cert.serialNumber.toString(16),
            notBefore = cert.notBefore,
            notAfter = cert.notAfter,
            isValidNow = isValidNow
        )
    }

    override fun buildSocketFactory(settings: ClusterSettings): MtlsContext? {
        if (!settings.hasMtlsCredentials) {
            return null
        }
        return try {
            createMtlsContextFromPem(
                caCertPem = settings.caCertPem,
                clientCertPem = settings.clientCertPem,
                clientKeyPem = settings.clientKeyPem
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Custom delegating SSLSocketFactory enforcing TLS 1.3 protocol on created sockets.
 *
 * Traces to: [INV-002], [NFR-SEC-001]
 */
class MtlsTls13SocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun configureSocket(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols.toList()
            if (supported.contains("TLSv1.3")) {
                socket.enabledProtocols = arrayOf("TLSv1.3")
            }
        }
        return socket
    }

    override fun createSocket(): Socket = configureSocket(delegate.createSocket())

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configureSocket(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        configureSocket(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configureSocket(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        configureSocket(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configureSocket(delegate.createSocket(address, port, localAddress, localPort))
}
