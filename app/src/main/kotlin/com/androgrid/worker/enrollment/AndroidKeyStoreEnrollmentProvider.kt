package com.androgrid.worker.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.androgrid.worker.network.MtlsContext
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Hardware-backed [KeyEnrollmentProvider] anchoring private keys in the Android KeyStore.
 * Enforces hardware isolation: private keys are non-exportable and never touch the filesystem.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class AndroidKeyStoreEnrollmentProvider : KeyEnrollmentProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
        load(null)
    }

    override fun generateKeyPair(alias: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE_PROVIDER
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    override fun generateCsrPem(alias: String, commonName: String): String {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Private key not found in AndroidKeyStore for alias: $alias")

        val cert = keyStore.getCertificate(alias)
            ?: throw IllegalStateException("Certificate/PublicKey not found in AndroidKeyStore for alias: $alias")

        return KeyEnrollmentUtils.buildCsrPem(cert.publicKey, privateKey, commonName)
    }

    override fun installCertificateChain(alias: String, clientCertPem: String, caCertPem: String) {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Private key not found in AndroidKeyStore for alias: $alias")

        val clientCerts = KeyEnrollmentUtils.parseCertificateChain(clientCertPem)
        require(clientCerts.isNotEmpty()) { "Client certificate chain must contain at least one certificate" }

        // Update KeyStore entry with CA-issued certificate chain while preserving hardware private key
        keyStore.setKeyEntry(alias, privateKey, null, clientCerts.toTypedArray())
    }

    override fun isEnrolled(alias: String): Boolean {
        if (!keyStore.containsAlias(alias)) return false
        val cert = keyStore.getCertificate(alias) as? X509Certificate ?: return false
        // A provisioned certificate is typically signed by the external Root CA rather than self-signed
        return cert.issuerX500Principal != cert.subjectX500Principal || keyStore.getCertificateChain(alias)?.size ?: 0 > 1
    }

    override fun createMtlsContext(alias: String, caCertPem: String): MtlsContext {
        // 1. Build TrustManager with Root CA
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

        // 2. Build KeyManager directly from AndroidKeyStore
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }

        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
        }

        return MtlsContext(
            socketFactory = sslContext.socketFactory,
            trustManager = trustManager
        )
    }

    override fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ENROLLMENT_ALIAS = "androgrid_worker_mtls_key"

        /**
         * Resolves the appropriate [KeyEnrollmentProvider] for the current environment.
         * Falls back gracefully to [SoftwareKeyEnrollmentProvider] if AndroidKeyStore provider is absent.
         */
        fun createDefault(): KeyEnrollmentProvider {
            return try {
                KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
                AndroidKeyStoreEnrollmentProvider()
            } catch (_: Exception) {
                SoftwareKeyEnrollmentProvider()
            }
        }
    }
}
