package com.androgrid.worker.enrollment

import com.androgrid.v1.EnrollWorkerRequest
import com.androgrid.v1.EnrollWorkerResponse
import com.androgrid.v1.EnrollmentServiceGrpcKt
import com.androgrid.worker.network.TestCertificateFixtures
import com.google.common.truth.Truth.assertThat
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Hermetic unit tests for the Bootstrap Enrollment Client verifying gRPC exchanges,
 * Root CA certificate fingerprint pinning, and security rejection boundaries.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class BootstrapEnrollmentClientTest {

    private lateinit var inProcessServer: Server
    private lateinit var inProcessChannel: ManagedChannel
    private val serverName = "bootstrap-enrollment-test-server"

    private val testCaCertPem = TestCertificateFixtures.CA_CERT_PEM
    private val testClientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM

    private lateinit var validFingerprint: String

    @Before
    fun setUp() {
        validFingerprint = KeyEnrollmentUtils.computeSha256Fingerprint(testCaCertPem)
    }

    @After
    fun tearDown() {
        if (::inProcessChannel.isInitialized) inProcessChannel.shutdownNow()
        if (::inProcessServer.isInitialized) inProcessServer.shutdownNow()
    }

    @Test
    fun enroll_successfulExchange_returnsIssuedCertificates() = runBlocking {
        val fakeService = object : EnrollmentServiceGrpcKt.EnrollmentServiceCoroutineImplBase() {
            override suspend fun enrollWorker(request: EnrollWorkerRequest): EnrollWorkerResponse {
                assertThat(request.pairingToken).isEqualTo("valid-token-123")
                assertThat(request.nodeId).isEqualTo("worker_node_01")
                assertThat(request.csrPem).isNotEmpty()
                return EnrollWorkerResponse.newBuilder()
                    .setSuccess(true)
                    .setClientCertPem(testClientCertPem)
                    .setCaCertPem(testCaCertPem)
                    .build()
            }
        }

        inProcessServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start()

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        val client = GrpcBootstrapEnrollmentClient(channelProvider = { _, _ -> inProcessChannel })

        val payload = EnrollmentPayload(
            clusterEndpoint = "127.0.0.1:50051",
            caFingerprint = validFingerprint,
            pairingToken = "valid-token-123"
        )

        val result = client.enroll(payload, csrPem = "-----BEGIN CERTIFICATE REQUEST-----\n...", effectiveNodeId = "worker_node_01")

        assertThat(result).isInstanceOf(EnrollmentResult.Success::class.java)
        val success = result as EnrollmentResult.Success
        assertThat(success.clientCertPem).isEqualTo(testClientCertPem)
        assertThat(success.caCertPem).isEqualTo(testCaCertPem)
    }

    @Test
    fun enroll_serverRejection_returnsFailureWithErrorMessage() = runBlocking {
        val fakeService = object : EnrollmentServiceGrpcKt.EnrollmentServiceCoroutineImplBase() {
            override suspend fun enrollWorker(request: EnrollWorkerRequest): EnrollWorkerResponse {
                return EnrollWorkerResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("Ephemeral pairing token has expired")
                    .build()
            }
        }

        inProcessServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start()

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        val client = GrpcBootstrapEnrollmentClient(channelProvider = { _, _ -> inProcessChannel })

        val payload = EnrollmentPayload(
            clusterEndpoint = "127.0.0.1:50051",
            caFingerprint = validFingerprint,
            pairingToken = "expired-token"
        )

        val result = client.enroll(payload, csrPem = "dummy-csr", effectiveNodeId = "worker_node_01")

        assertThat(result).isInstanceOf(EnrollmentResult.Failure::class.java)
        val failure = result as EnrollmentResult.Failure
        assertThat(failure.errorMessage).contains("Ephemeral pairing token has expired")
        assertThat(failure.isSecurityViolation).isFalse()
    }

    @Test
    fun enroll_caFingerprintMismatch_triggersSecurityViolation() = runBlocking {
        val fakeService = object : EnrollmentServiceGrpcKt.EnrollmentServiceCoroutineImplBase() {
            override suspend fun enrollWorker(request: EnrollWorkerRequest): EnrollWorkerResponse {
                return EnrollWorkerResponse.newBuilder()
                    .setSuccess(true)
                    .setClientCertPem(testClientCertPem)
                    .setCaCertPem(testCaCertPem)
                    .build()
            }
        }

        inProcessServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start()

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        val client = GrpcBootstrapEnrollmentClient(channelProvider = { _, _ -> inProcessChannel })

        // Intentionally provide a different fingerprint in payload
        val forgedFingerprint = "0".repeat(64)
        val payload = EnrollmentPayload(
            clusterEndpoint = "127.0.0.1:50051",
            caFingerprint = forgedFingerprint,
            pairingToken = "token"
        )

        val result = client.enroll(payload, csrPem = "dummy-csr", effectiveNodeId = "worker_node_01")

        assertThat(result).isInstanceOf(EnrollmentResult.Failure::class.java)
        val failure = result as EnrollmentResult.Failure
        assertThat(failure.isSecurityViolation).isTrue()
        assertThat(failure.errorMessage).contains("fingerprint mismatch")
    }
}
