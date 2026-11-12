package com.androgrid.worker.enrollment

import com.androgrid.v1.EnrollWorkerRequest
import com.androgrid.v1.EnrollmentServiceGrpcKt
import com.androgrid.worker.network.MtlsContext
import com.androgrid.worker.network.MtlsTls13SocketFactory
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Result model representing outcomes of the bootstrap device enrollment exchange.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
sealed class EnrollmentResult {
    /**
     * Successfully redeemed pairing token and acquired signed X.509 client certificate and Root CA.
     */
    data class Success(
        val clientCertPem: String,
        val caCertPem: String
    ) : EnrollmentResult()

    /**
     * Enrollment attempt rejected or failed due to network, token invalidity, or security violation.
     */
    data class Failure(
        val errorMessage: String,
        val isSecurityViolation: Boolean = false
    ) : EnrollmentResult()
}

/**
 * gRPC and HTTP client contract for single-use bootstrap device enrollment.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
interface BootstrapEnrollmentClient {
    /**
     * Executes the bootstrap enrollment RPC to exchange pairing token and CSR for signed mTLS credentials.
     *
     * @param payload Scanned or provided [EnrollmentPayload].
     * @param csrPem PEM-encoded PKCS#10 Certificate Signing Request.
     * @param effectiveNodeId Node identifier to bind the certificate Common Name to.
     * @return [EnrollmentResult] representing the outcome.
     */
    suspend fun enroll(
        payload: EnrollmentPayload,
        csrPem: String,
        effectiveNodeId: String
    ): EnrollmentResult
}

/**
 * Production gRPC implementation of [BootstrapEnrollmentClient].
 * Connects to orchestrator EnrollmentService, enforces Root CA SHA-256 fingerprint pinning,
 * and validates issued credentials.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class GrpcBootstrapEnrollmentClient(
    private val channelProvider: ((host: String, port: Int) -> ManagedChannel)? = null
) : BootstrapEnrollmentClient {

    override suspend fun enroll(
        payload: EnrollmentPayload,
        csrPem: String,
        effectiveNodeId: String
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        if (payload.isExpired()) {
            return@withContext EnrollmentResult.Failure("Pairing token has expired before enrollment initiation")
        }

        val (host, port) = payload.parseEndpoint()
        var customChannel: ManagedChannel? = null
        val channel = if (channelProvider != null) {
            channelProvider.invoke(host, port)
        } else {
            val created = buildPinnedChannel(host, port, payload.caFingerprint)
            customChannel = created
            created
        }

        try {
            val stub = EnrollmentServiceGrpcKt.EnrollmentServiceCoroutineStub(channel)
            val request = EnrollWorkerRequest.newBuilder()
                .setPairingToken(payload.pairingToken)
                .setNodeId(effectiveNodeId)
                .setCsrPem(csrPem)
                .build()

            val response = stub.enrollWorker(request)

            if (!response.success) {
                return@withContext EnrollmentResult.Failure(
                    errorMessage = if (response.errorMessage.isNotBlank()) response.errorMessage else "Enrollment rejected by control plane"
                )
            }

            val clientCert = response.clientCertPem.trim()
            val caCert = response.caCertPem.trim()

            if (clientCert.isEmpty() || caCert.isEmpty()) {
                return@withContext EnrollmentResult.Failure("Enrollment succeeded but server returned empty certificate materials")
            }

            // Verify Root CA SHA-256 fingerprint matches pinned fingerprint in QR payload
            val returnedCaFingerprint = KeyEnrollmentUtils.computeSha256Fingerprint(caCert)
            if (!returnedCaFingerprint.equals(payload.caFingerprint, ignoreCase = true)) {
                return@withContext EnrollmentResult.Failure(
                    errorMessage = "Security violation: Root CA certificate fingerprint mismatch (expected: ${payload.caFingerprint}, received: $returnedCaFingerprint)",
                    isSecurityViolation = true
                )
            }

            EnrollmentResult.Success(
                clientCertPem = clientCert,
                caCertPem = caCert
            )
        } catch (e: Exception) {
            EnrollmentResult.Failure("Enrollment RPC failed: ${e.message}")
        } finally {
            customChannel?.let {
                try {
                    it.shutdown()
                    it.awaitTermination(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    it.shutdownNow()
                }
            }
        }
    }

    internal fun createPinningTrustManager(expectedFingerprintHex: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }

                // Locate the pinned trust anchor in the presentation chain
                val anchorIndex = chain.indexOfFirst { cert ->
                    val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                        .joinToString("") { "%02x".format(it) }
                    fp.equals(expectedFingerprintHex, ignoreCase = true)
                }

                if (anchorIndex < 0) {
                    throw CertificateException("Server certificate chain does not contain trusted anchor with SHA-256 fingerprint: $expectedFingerprintHex")
                }

                // Verify chronological validity for all certificates from leaf up to anchor
                for (i in 0..anchorIndex) {
                    chain[i].checkValidity()
                }

                // Verify cryptographic signature path and CA authorization from leaf (chain[0]) to anchor (chain[anchorIndex])
                for (i in 0 until anchorIndex) {
                    val subjectCert = chain[i]
                    val issuerCert = chain[i + 1]

                    // RFC 5280 Section 4.2.1.9: Intermediate signers must be authorized CAs
                    if (issuerCert.basicConstraints < 0) {
                        throw CertificateException(
                            "Certificate at index ${i + 1} (${issuerCert.subjectX500Principal.name}) " +
                                "is not authorized as a CA (basicConstraints < 0)"
                        )
                    }

                    // RFC 5280 Section 4.2.1.3: Intermediate signers must have keyCertSign asserted
                    val keyUsage = issuerCert.keyUsage
                    if (keyUsage != null && (keyUsage.size <= 5 || !keyUsage[5])) {
                        throw CertificateException(
                            "Certificate at index ${i + 1} (${issuerCert.subjectX500Principal.name}) " +
                                "lacks keyCertSign key usage"
                        )
                    }

                    try {
                        subjectCert.verify(issuerCert.publicKey)
                    } catch (e: Exception) {
                        throw CertificateException("Invalid certificate signature chain at index $i: ${e.message}", e)
                    }
                }

                // If anchor is self-signed (Root CA), verify its self-signature
                val anchorCert = chain[anchorIndex]
                if (anchorCert.issuerX500Principal == anchorCert.subjectX500Principal) {
                    try {
                        anchorCert.verify(anchorCert.publicKey)
                    } catch (e: Exception) {
                        throw CertificateException("Invalid root certificate self-signature: ${e.message}", e)
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    private fun buildPinnedChannel(host: String, port: Int, expectedFingerprintHex: String): ManagedChannel {
        val pinningTrustManager = createPinningTrustManager(expectedFingerprintHex)

        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        }

        return OkHttpChannelBuilder.forAddress(host, port)
            .useTransportSecurity()
            .sslSocketFactory(MtlsTls13SocketFactory(sslContext.socketFactory))
            .tlsConnectionSpec(
                MtlsContext.TLS_13_VERSIONS,
                MtlsContext.TLS_13_CIPHERS
            )
            .overrideAuthority(host)
            .build()
    }
}
