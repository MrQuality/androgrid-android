package com.androgrid.worker.enrollment

import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.TestCertificateFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for EnrollmentCoordinator validating the end-to-end orchestration of
 * key generation, CSR exchange, certificate installation, and repository state synchronization.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class EnrollmentCoordinatorTest {

    private lateinit var keyProvider: SoftwareKeyEnrollmentProvider
    private lateinit var repository: InMemoryClusterSettingsRepository
    private val keyAlias = "test_coordinator_alias"
    private val defaultNodeId = "worker_default_test"

    private val testCaCertPem = TestCertificateFixtures.CA_CERT_PEM
    private val testClientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM

    private lateinit var validFingerprint: String

    @Before
    fun setUp() {
        keyProvider = SoftwareKeyEnrollmentProvider()
        repository = InMemoryClusterSettingsRepository()
        validFingerprint = KeyEnrollmentUtils.computeSha256Fingerprint(testCaCertPem)
    }

    @Test
    fun executeEnrollment_successfulFlow_provisionsKeyStoreAndUpdatesRepository() = runBlocking {
        val fakeClient = object : BootstrapEnrollmentClient {
            override suspend fun enroll(
                payload: EnrollmentPayload,
                csrPem: String,
                effectiveNodeId: String
            ): EnrollmentResult {
                assertThat(effectiveNodeId).isEqualTo("custom_node_42")
                return EnrollmentResult.Success(
                    clientCertPem = testClientCertPem,
                    caCertPem = testCaCertPem
                )
            }
        }

        val coordinator = EnrollmentCoordinator(
            keyProvider = keyProvider,
            enrollmentClient = fakeClient,
            settingsRepository = repository,
            keyAlias = keyAlias
        )

        val payload = EnrollmentPayload(
            clusterEndpoint = "192.168.1.150:50051",
            caFingerprint = validFingerprint,
            pairingToken = "token-success-123",
            nodeId = "custom_node_42"
        )

        val result = coordinator.executeEnrollment(payload, defaultNodeId)

        assertThat(result).isInstanceOf(EnrollmentResult.Success::class.java)
        assertThat(keyProvider.isEnrolled(keyAlias)).isTrue()

        val updatedSettings = repository.getSettings()
        assertThat(updatedSettings.wifiHost).isEqualTo("192.168.1.150")
        assertThat(updatedSettings.wifiPort).isEqualTo(50051)
        assertThat(updatedSettings.caCertPem).isEqualTo(testCaCertPem)
        assertThat(updatedSettings.clientCertPem).isEqualTo(testClientCertPem)
        assertThat(updatedSettings.hasMtlsCredentials).isTrue()
    }

    @Test
    fun executeEnrollment_expiredToken_failsBeforeKeyGeneration() = runBlocking {
        var clientInvoked = false
        val fakeClient = object : BootstrapEnrollmentClient {
            override suspend fun enroll(
                payload: EnrollmentPayload,
                csrPem: String,
                effectiveNodeId: String
            ): EnrollmentResult {
                clientInvoked = true
                return EnrollmentResult.Failure("Should not be called")
            }
        }

        val coordinator = EnrollmentCoordinator(
            keyProvider = keyProvider,
            enrollmentClient = fakeClient,
            settingsRepository = repository,
            keyAlias = keyAlias
        )

        val pastEpoch = (System.currentTimeMillis() / 1000) - 60
        val payload = EnrollmentPayload(
            clusterEndpoint = "192.168.1.150:50051",
            caFingerprint = validFingerprint,
            pairingToken = "token-expired",
            expiresAtEpochSeconds = pastEpoch
        )

        val result = coordinator.executeEnrollment(payload, defaultNodeId)

        assertThat(result).isInstanceOf(EnrollmentResult.Failure::class.java)
        val failure = result as EnrollmentResult.Failure
        assertThat(failure.errorMessage).contains("expired")
        assertThat(clientInvoked).isFalse()
        assertThat(keyProvider.isEnrolled(keyAlias)).isFalse()
    }

    @Test
    fun executeEnrollment_clientRejection_doesNotUpdateRepository() = runBlocking {
        val fakeClient = object : BootstrapEnrollmentClient {
            override suspend fun enroll(
                payload: EnrollmentPayload,
                csrPem: String,
                effectiveNodeId: String
            ): EnrollmentResult {
                return EnrollmentResult.Failure("Single-use token already redeemed")
            }
        }

        val coordinator = EnrollmentCoordinator(
            keyProvider = keyProvider,
            enrollmentClient = fakeClient,
            settingsRepository = repository,
            keyAlias = keyAlias
        )

        val payload = EnrollmentPayload(
            clusterEndpoint = "192.168.1.150:50051",
            caFingerprint = validFingerprint,
            pairingToken = "token-used"
        )

        val result = coordinator.executeEnrollment(payload, defaultNodeId)

        assertThat(result).isInstanceOf(EnrollmentResult.Failure::class.java)
        val failure = result as EnrollmentResult.Failure
        assertThat(failure.errorMessage).contains("already redeemed")

        // Repository remains unconfigured
        assertThat(repository.getSettings().hasMtlsCredentials).isFalse()
    }
}
