package com.androgrid.worker.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.androgrid.worker.network.MtlsContext
import com.androgrid.worker.network.MtlsTls13SocketFactory
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Hardware-backed [KeyEnrollmentProvider] anchoring private keys in the Android KeyStore.
 * Enforces hardware isolation: private keys are non-exportable and never touch the filesystem.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class AndroidKeyStoreEnrollmentProvider internal constructor(
    private val keyStore: KeyStore
) : KeyEnrollmentProvider {

    constructor() : this(
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    )

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

    internal fun buildAliasKeyManager(alias: String): X509ExtendedKeyManager {
        require(keyStore.containsAlias(alias)) { "Alias '$alias' not found in KeyStore" }
        val clientCert = keyStore.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("Certificate for alias '$alias' not found or invalid")
        val keyAlgorithm = clientCert.publicKey.algorithm

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }
        val defaultKm = kmf.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
            ?: throw IllegalStateException("No X509KeyManager found in KeyManagerFactory")

        return object : X509ExtendedKeyManager() {
            private fun matchesKeyType(types: Array<out String>?): Boolean {
                if (types.isNullOrEmpty()) return true
                return types.any { type ->
                    type.contains(keyAlgorithm, ignoreCase = true)
                }
            }

            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (matchesKeyType(keyType)) alias else null

            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                if (matchesKeyType(keyType)) alias else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == null || keyType.contains(keyAlgorithm, ignoreCase = true)) arrayOf(alias) else null

            override fun getCertificateChain(aliasParam: String?): Array<X509Certificate>? {
                if (aliasParam != null && aliasParam != alias) return null
                val chain = keyStore.getCertificateChain(alias) ?: return null
                return chain.filterIsInstance<X509Certificate>().toTypedArray()
            }

            override fun getPrivateKey(aliasParam: String?): PrivateKey? {
                if (aliasParam != null && aliasParam != alias) return null
                return keyStore.getKey(alias, null) as? PrivateKey
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                defaultKm.getServerAliases(keyType, issuers)

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                defaultKm.chooseServerAlias(keyType, issuers, socket)

            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                (defaultKm as? X509ExtendedKeyManager)?.chooseEngineServerAlias(keyType, issuers, engine)
        }
    }

    override fun createMtlsContext(alias: String, caCertPem: String): MtlsContext {
        val aliasKeyManager = buildAliasKeyManager(alias)

        // 1. Build TrustStore with CA certificate chain
        val caCerts = KeyEnrollmentUtils.parseCertificateChain(caCertPem)
        require(caCerts.isNotEmpty()) { "No valid CA certificates found in PEM" }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            caCerts.forEachIndexed { index, cert ->
                setCertificateEntry("ca_root_$index", cert)
            }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        val sslContext = MtlsContext.createSslContext().apply {
            init(arrayOf(aliasKeyManager), arrayOf(trustManager), SecureRandom())
        }

        return MtlsContext(
            socketFactory = MtlsTls13SocketFactory(sslContext.socketFactory),
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
