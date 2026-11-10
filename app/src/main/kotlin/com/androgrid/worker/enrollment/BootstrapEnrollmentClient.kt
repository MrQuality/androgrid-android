package com.androgrid.worker.enrollment

import com.androgrid.v1.EnrollWorkerRequest
import com.androgrid.v1.EnrollmentServiceGrpcKt
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

    private fun buildPinnedChannel(host: String, port: Int, expectedFingerprintHex: String): ManagedChannel {
        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }

                // Check if any certificate in chain matches the pinned SHA-256 fingerprint
                val matches = chain.any { cert ->
                    val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                        .joinToString("") { "%02x".format(it) }
                    fp.equals(expectedFingerprintHex, ignoreCase = true)
                }

                if (!matches) {
                    throw CertificateException("Server certificate SHA-256 fingerprint does not match QR pinned fingerprint")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        }

        return OkHttpChannelBuilder.forAddress(host, port)
            .sslSocketFactory(sslContext.socketFactory)
            .overrideAuthority(host)
            .build()
    }
}
