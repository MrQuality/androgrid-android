package com.androgrid.worker.enrollment

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Utility routines for cryptographic certificate handling, SHA-256 fingerprinting, and PKCS#10 CSR serialization.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
object KeyEnrollmentUtils {

    private const val CERT_TYPE = "X.509"
    private const val SHA256_ALGORITHM = "SHA-256"

    /**
     * Computes the lowercase 64-character hexadecimal SHA-256 fingerprint of an X.509 certificate.
     *
     * @param certPem PEM-encoded X.509 certificate string.
     * @return Lowercase 64-character hexadecimal SHA-256 fingerprint.
     */
    fun computeSha256Fingerprint(certPem: String): String {
        val cert = parseX509Certificate(certPem)
        return computeSha256Fingerprint(cert.encoded)
    }

    /**
     * Computes the lowercase 64-character hexadecimal SHA-256 fingerprint of raw DER certificate bytes.
     *
     * @param certDerBytes Raw DER-encoded certificate bytes.
     * @return Lowercase 64-character hexadecimal SHA-256 fingerprint.
     */
    fun computeSha256Fingerprint(certDerBytes: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM).digest(certDerBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parses a PEM-encoded X.509 certificate string into an [X509Certificate] instance.
     *
     * @param certPem PEM-encoded certificate string.
     * @return Parsed [X509Certificate] instance.
     */
    fun parseX509Certificate(certPem: String): X509Certificate {
        require(certPem.isNotBlank()) { "Certificate PEM must not be empty" }
        val cf = CertificateFactory.getInstance(CERT_TYPE)
        return cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray(Charsets.UTF_8))) as X509Certificate
    }

    /**
     * Parses multiple PEM-encoded X.509 certificates (e.g. certificate chain).
     *
     * @param certChainPem PEM-encoded certificate chain string.
     * @return List of parsed [X509Certificate] instances.
     */
    fun parseCertificateChain(certChainPem: String): List<X509Certificate> {
        require(certChainPem.isNotBlank()) { "Certificate chain PEM must not be empty" }
        val cf = CertificateFactory.getInstance(CERT_TYPE)
        val certs = cf.generateCertificates(ByteArrayInputStream(certChainPem.toByteArray(Charsets.UTF_8)))
        return certs.filterIsInstance<X509Certificate>()
    }

    /**
     * Parses a PEM-encoded PKCS#8 EC or RSA private key into a [PrivateKey] instance.
     *
     * @param keyPem PEM-encoded private key string.
     * @return Parsed [PrivateKey] instance.
     */
    fun parsePrivateKey(keyPem: String): PrivateKey {
        require(keyPem.isNotBlank()) { "Private key PEM must not be empty" }
        val cleanKey = keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = try {
            java.util.Base64.getDecoder().decode(cleanKey)
        } catch (e: Exception) {
            throw IllegalArgumentException("Client private key PEM contains invalid base64 data", e)
        }

        val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
        return try {
            java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec)
        } catch (_: Exception) {
            try {
                java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            } catch (e2: Exception) {
                throw IllegalArgumentException("Client private key is neither valid EC nor RSA PKCS#8 key", e2)
            }
        }
    }

    /**
     * Constructs a PKCS#10 Certificate Signing Request (CSR) with Subject Common Name set to commonName,
     * signed by the provided private key using SHA256withECDSA.
     *
     * @param publicKey Public key to include in CSR.
     * @param privateKey Private key to sign the CSR (can be an AndroidKeyStore handle).
     * @param commonName Subject Common Name (e.g. node_id).
     * @return PEM-encoded PKCS#10 CSR string.
     */
    fun buildCsrPem(publicKey: PublicKey, privateKey: PrivateKey, commonName: String): String {
        val x500Name = X500Name("CN=$commonName")
        val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val requestBuilder = PKCS10CertificationRequestBuilder(x500Name, subjectPublicKeyInfo)

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
        val csr = requestBuilder.build(signer)

        val stringWriter = StringWriter()
        JcaPEMWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(csr)
        }
        return stringWriter.toString()
    }
}
