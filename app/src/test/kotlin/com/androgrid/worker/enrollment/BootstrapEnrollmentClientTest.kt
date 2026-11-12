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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

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

    @Test
    fun pinningTrustManager_validSignedChain_passesVerification() {
        val client = GrpcBootstrapEnrollmentClient()
        val trustManager = client.createPinningTrustManager(validFingerprint)

        val clientCert = KeyEnrollmentUtils.parseX509Certificate(testClientCertPem)
        val caCert = KeyEnrollmentUtils.parseX509Certificate(testCaCertPem)

        val chain: Array<X509Certificate> = arrayOf(clientCert, caCert)
        // Should succeed without throwing CertificateException
        trustManager.checkServerTrusted(chain, "ECDHE_ECDSA")
    }

    @Test
    fun pinningTrustManager_mitmRogueLeafWithAppendedCa_throwsCertificateException() {
        val client = GrpcBootstrapEnrollmentClient()
        val trustManager = client.createPinningTrustManager(validFingerprint)

        val caCert = KeyEnrollmentUtils.parseX509Certificate(testCaCertPem)

        // Generate an attacker self-signed certificate not issued by caCert
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val attackerKeyPair = kpg.generateKeyPair()
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(attackerKeyPair.private)
        val now = System.currentTimeMillis()
        val certBuilder = JcaX509v3CertificateBuilder(
            X500Name("CN=Attacker Rogue Leaf"),
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 3_600_000),
            X500Name("CN=Attacker Rogue Leaf"),
            attackerKeyPair.public
        )
        val attackerCert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        // MITM attack: Attacker presents [attackerCert, realCaCert]
        val rogueChain: Array<X509Certificate> = arrayOf(attackerCert, caCert)

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(rogueChain, "ECDHE_ECDSA")
        }
        assertThat(exception.message).contains("Invalid certificate signature chain")
    }

    @Test
    fun pinningTrustManager_missingRootCaInChain_throwsCertificateException() {
        val client = GrpcBootstrapEnrollmentClient()
        val trustManager = client.createPinningTrustManager(validFingerprint)

        val clientCert = KeyEnrollmentUtils.parseX509Certificate(testClientCertPem)

        // Presentation without the pinned root CA
        val chain: Array<X509Certificate> = arrayOf(clientCert)

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(chain, "ECDHE_ECDSA")
        }
        assertThat(exception.message).contains("does not contain trusted anchor")
    }

    @Test
    fun pinningTrustManager_leafAsIntermediateCa_throwsCertificateException() {
        val client = GrpcBootstrapEnrollmentClient()
        val trustManager = client.createPinningTrustManager(validFingerprint)

        val caCert = KeyEnrollmentUtils.parseX509Certificate(testCaCertPem)
        val clientCert = KeyEnrollmentUtils.parseX509Certificate(testClientCertPem)
        val clientPrivateKey = KeyEnrollmentUtils.parsePrivateKey(TestCertificateFixtures.clientPrivateKeyPem)

        // Generate a forged server cert signed by clientCert (which has basicConstraints == -1)
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val serverKeyPair = kpg.generateKeyPair()
        val sigAlg = if (clientPrivateKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(sigAlg).build(clientPrivateKey)
        val now = System.currentTimeMillis()
        val certBuilder = JcaX509v3CertificateBuilder(
            X500Name(clientCert.subjectX500Principal.name),
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 3_600_000),
            X500Name("CN=Forged Server"),
            serverKeyPair.public
        )
        val forgedServerCert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        // Rogue chain: [forgedServerCert, clientCert, caCert]
        val rogueChain: Array<X509Certificate> = arrayOf(forgedServerCert, clientCert, caCert)

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(rogueChain, "ECDHE_ECDSA")
        }
        assertThat(exception.message).contains("is not authorized as a CA (basicConstraints < 0)")
    }
}
