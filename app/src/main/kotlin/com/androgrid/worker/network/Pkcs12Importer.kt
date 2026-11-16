package com.androgrid.worker.network

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Extracted PEM credentials from a PKCS#12 (.p12) bundle.
 *
 * Traces to: `[INV-002]`, `[NFR-SEC-001]`, `[REQ-OP-001]`
 *
 * @property clientCertPem PEM-encoded X.509 client leaf certificate and any non-self-signed intermediates.
 * @property clientKeyPem PKCS#8 PEM-encoded client private key.
 * @property caCertPem Optional PEM-encoded self-signed root CA certificate(s) when present in the bundle.
 */
data class Pkcs12Credentials(
    val clientCertPem: String,
    val clientKeyPem: String,
    val caCertPem: String? = null
)

/**
 * Result of parsing a PKCS#12 (.p12) keystore bundle.
 *
 * Traces to: `[INV-002]`, `[NFR-SEC-001]`, `[REQ-OP-001]`
 */
sealed class Pkcs12ImportResult {
    data class Success(val credentials: Pkcs12Credentials) : Pkcs12ImportResult()
    data class PasswordRequired(val reason: String) : Pkcs12ImportResult()
    data class Failure(val userMessage: String, val cause: Throwable? = null) : Pkcs12ImportResult()
}

/**
 * Parses PKCS#12 (.p12) bundles into PEM-encoded mTLS credentials, falling back from the
 * platform KeyStore provider to BouncyCastle for OpenSSL 3.x PBES2/AES-256-CBC/SHA-256 bundles.
 *
 * Traces to: `[INV-002]`, `[NFR-SEC-001]`, `[REQ-OP-001]`
 */
object Pkcs12Importer {

    private val mimeEncoder: Base64.Encoder =
        Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))

    private val bcProvider: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    /**
     * Parses raw `.p12` bytes using [password], trying [primaryLoader] first and falling back
     * to BouncyCastle when the platform provider rejects modern OpenSSL 3.x ciphers.
     *
     * @param bytes Raw PKCS#12 file bytes.
     * @param password Bundle passphrase (defaults to empty for passwordless `.p12` exports).
     * @param primaryLoader Testable primary KeyStore loader (defaults to platform `"PKCS12"`).
     * @return [Pkcs12ImportResult] describing extracted credentials, password prompt need, or error.
     */
    internal fun importWithLoader(
        bytes: ByteArray,
        password: CharArray = charArrayOf(),
        primaryLoader: (ByteArray, CharArray) -> KeyStore = ::loadPlatformPkcs12
    ): Pkcs12ImportResult {
        if (bytes.isEmpty()) {
            return Pkcs12ImportResult.Failure("PKCS#12 file is empty.")
        }

        val loadResult = loadKeyStoreWithFallback(bytes, password, primaryLoader)
        val ks = loadResult.getOrElse { err ->
            return if (isPasswordOrMacError(err)) {
                Pkcs12ImportResult.PasswordRequired(
                    err.message ?: "PKCS#12 bundle requires a passphrase or MAC verification failed."
                )
            } else {
                Pkcs12ImportResult.Failure(
                    "Failed to parse PKCS#12 bundle: ${err.message ?: err.javaClass.simpleName}",
                    err
                )
            }
        }

        return extractCredentials(ks, password)
    }

    /**
     * Public entry point for parsing `.p12` bytes with the default platform-to-BouncyCastle fallback.
     *
     * @param bytes Raw PKCS#12 file bytes.
     * @param password Bundle passphrase (defaults to empty).
     * @return [Pkcs12ImportResult] describing extracted credentials, password prompt need, or error.
     */
    fun import(bytes: ByteArray, password: CharArray = charArrayOf()): Pkcs12ImportResult {
        return importWithLoader(bytes, password, ::loadPlatformPkcs12)
    }

    /**
     * Loads a PKCS#12 [KeyStore], trying [primaryLoader] first and falling back to BouncyCastle.
     *
     * Traces to: `[INV-002]`, `[NFR-SEC-001]`
     */
    internal fun loadKeyStoreWithFallback(
        bytes: ByteArray,
        password: CharArray,
        primaryLoader: (ByteArray, CharArray) -> KeyStore = ::loadPlatformPkcs12
    ): Result<KeyStore> {
        val platformErr = try {
            return Result.success(primaryLoader(bytes, password))
        } catch (e: Exception) {
            e
        }

        return try {
            val ks = KeyStore.getInstance("PKCS12", bcProvider).apply {
                load(ByteArrayInputStream(bytes), password)
            }
            Result.success(ks)
        } catch (bcErr: Exception) {
            bcErr.addSuppressed(platformErr)
            Result.failure(bcErr)
        }
    }

    private fun loadPlatformPkcs12(bytes: ByteArray, password: CharArray): KeyStore {
        return KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(bytes), password)
        }
    }

    private fun extractCredentials(ks: KeyStore, password: CharArray): Pkcs12ImportResult {
        val aliases = ks.aliases().toList()
        if (aliases.isEmpty()) {
            return Pkcs12ImportResult.Failure("PKCS#12 bundle contains no certificate or key entries.")
        }

        val keyAlias = aliases.firstOrNull {
            try {
                ks.entryInstanceOf(it, KeyStore.PrivateKeyEntry::class.java)
            } catch (_: Exception) {
                ks.isKeyEntry(it)
            }
        } ?: return Pkcs12ImportResult.Failure("PKCS#12 bundle contains no private key entry.")

        val key = try {
            ks.getKey(keyAlias, password) as? PrivateKey
        } catch (e: UnrecoverableKeyException) {
            return Pkcs12ImportResult.PasswordRequired(
                e.message ?: "Private key in PKCS#12 bundle requires a passphrase."
            )
        } catch (e: Exception) {
            return Pkcs12ImportResult.Failure(
                "Failed to extract private key from alias '$keyAlias': ${e.message}",
                e
            )
        } ?: return Pkcs12ImportResult.Failure("Alias '$keyAlias' does not contain an extractable private key.")

        val keyEncoded = key.encoded
            ?: return Pkcs12ImportResult.Failure("Private key in alias '$keyAlias' has no PKCS#8 encoding.")

        val chain = ks.getCertificateChain(keyAlias)?.filterIsInstance<X509Certificate>().orEmpty()
            .ifEmpty { listOfNotNull(ks.getCertificate(keyAlias) as? X509Certificate) }

        if (chain.isEmpty()) {
            return Pkcs12ImportResult.Failure("Alias '$keyAlias' has no X.509 certificate.")
        }

        // Keep leaf + non-self-signed intermediates in clientCertPem; route self-signed roots to caCertPem.
        val leaf = chain.first()
        val intermediates = chain.drop(1).filter { !isSelfSigned(it) }
        val clientChainPem = (listOf(leaf) + intermediates)
            .joinToString("\n") { toPem("CERTIFICATE", it.encoded) }

        val keyPem = toPem("PRIVATE KEY", keyEncoded)

        val rootCerts = chain.drop(1).filter { isSelfSigned(it) }.toMutableList()
        aliases.filter { ks.isCertificateEntry(it) }.forEach { caAlias ->
            (ks.getCertificate(caAlias) as? X509Certificate)?.let { cert ->
                if (!rootCerts.any { it.encoded.contentEquals(cert.encoded) }) {
                    rootCerts.add(cert)
                }
            }
        }
        val caPem = if (rootCerts.isNotEmpty()) {
            rootCerts.joinToString("\n") { toPem("CERTIFICATE", it.encoded) }
        } else {
            null
        }

        return Pkcs12ImportResult.Success(
            Pkcs12Credentials(
                clientCertPem = clientChainPem,
                clientKeyPem = keyPem,
                caCertPem = caPem
            )
        )
    }

    private fun isSelfSigned(cert: X509Certificate): Boolean {
        if (cert.subjectX500Principal != cert.issuerX500Principal) {
            return false
        }
        return try {
            cert.verify(cert.publicKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun isPasswordOrMacError(err: Throwable): Boolean {
        var cur: Throwable? = err
        while (cur != null) {
            if (cur is UnrecoverableKeyException ||
                cur is BadPaddingException ||
                cur is AEADBadTagException
            ) {
                return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("wrong password") ||
                msg.contains("mac invalid") ||
                msg.contains("mac check") ||
                msg.contains("keystore password was incorrect")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun toPem(type: String, derBytes: ByteArray): String {
        val body = mimeEncoder.encodeToString(derBytes)
        return "-----BEGIN $type-----\n$body\n-----END $type-----"
    }
}
