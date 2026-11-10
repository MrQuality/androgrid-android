package com.androgrid.worker.enrollment

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for EnrollmentPayload parsing, validation, and endpoint resolution.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnrollmentPayloadTest {

    private val validSha256Fingerprint = "a1b2c3d4e5f60718293a4b5c6d7e8f901234567890abcdef1234567890abcdef"

    @Test
    fun parseJson_validCompletePayload_succeeds() {
        val json = """
            {
                "cluster_endpoint": "192.168.1.100:50051",
                "ca_fingerprint": "$validSha256Fingerprint",
                "pairing_token": "token-xyz-12345",
                "node_id": "worker_pixel_7",
                "expires_at_epoch_s": 1800000000
            }
        """.trimIndent()

        val payload = EnrollmentPayloadParser.parse(json)

        assertThat(payload.clusterEndpoint).isEqualTo("192.168.1.100:50051")
        assertThat(payload.caFingerprint).isEqualTo(validSha256Fingerprint.lowercase())
        assertThat(payload.pairingToken).isEqualTo("token-xyz-12345")
        assertThat(payload.nodeId).isEqualTo("worker_pixel_7")
        assertThat(payload.expiresAtEpochSeconds).isEqualTo(1800000000L)
    }

    @Test
    fun parseJson_minimalRequiredFields_succeeds() {
        val json = """
            {
                "cluster_endpoint": "grpc.cluster.domain.com:443",
                "ca_fingerprint": "$validSha256Fingerprint",
                "pairing_token": "single-use-token-77"
            }
        """.trimIndent()

        val payload = EnrollmentPayloadParser.parse(json)

        assertThat(payload.clusterEndpoint).isEqualTo("grpc.cluster.domain.com:443")
        assertThat(payload.caFingerprint).isEqualTo(validSha256Fingerprint.lowercase())
        assertThat(payload.pairingToken).isEqualTo("single-use-token-77")
        assertThat(payload.nodeId).isNull()
        assertThat(payload.expiresAtEpochSeconds).isNull()
    }

    @Test
    fun parseUri_validAndroGridScheme_succeeds() {
        val uri = "androgrid://enroll?endpoint=10.0.0.5:50051&token=tok-99&ca=$validSha256Fingerprint&node=custom_node_01&expires=1799999999"

        val payload = EnrollmentPayloadParser.parse(uri)

        assertThat(payload.clusterEndpoint).isEqualTo("10.0.0.5:50051")
        assertThat(payload.pairingToken).isEqualTo("tok-99")
        assertThat(payload.caFingerprint).isEqualTo(validSha256Fingerprint.lowercase())
        assertThat(payload.nodeId).isEqualTo("custom_node_01")
        assertThat(payload.expiresAtEpochSeconds).isEqualTo(1799999999L)
    }

    @Test
    fun parse_blankString_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentPayloadParser.parse("   ")
        }
    }

    @Test
    fun parseJson_missingEndpoint_throwsIllegalArgumentException() {
        val json = """
            {
                "ca_fingerprint": "$validSha256Fingerprint",
                "pairing_token": "token-only"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentPayloadParser.parse(json)
        }
    }

    @Test
    fun parseJson_missingToken_throwsIllegalArgumentException() {
        val json = """
            {
                "cluster_endpoint": "192.168.1.100:50051",
                "ca_fingerprint": "$validSha256Fingerprint"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentPayloadParser.parse(json)
        }
    }

    @Test
    fun parseJson_invalidFingerprintLength_throwsIllegalArgumentException() {
        val json = """
            {
                "cluster_endpoint": "192.168.1.100:50051",
                "ca_fingerprint": "too-short",
                "pairing_token": "token-123"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentPayloadParser.parse(json)
        }
    }

    @Test
    fun parseJson_invalidFingerprintCharacters_throwsIllegalArgumentException() {
        val badHex = "z".repeat(64)
        val json = """
            {
                "cluster_endpoint": "192.168.1.100:50051",
                "ca_fingerprint": "$badHex",
                "pairing_token": "token-123"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentPayloadParser.parse(json)
        }
    }

    @Test
    fun parseEndpoint_hostAndPortSeparation() {
        val payload1 = EnrollmentPayload(
            clusterEndpoint = "192.168.1.50:50051",
            caFingerprint = validSha256Fingerprint,
            pairingToken = "token"
        )
        val (host1, port1) = payload1.parseEndpoint()
        assertThat(host1).isEqualTo("192.168.1.50")
        assertThat(port1).isEqualTo(50051)

        val payload2 = EnrollmentPayload(
            clusterEndpoint = "grpc.cluster.domain.com",
            caFingerprint = validSha256Fingerprint,
            pairingToken = "token"
        )
        val (host2, port2) = payload2.parseEndpoint(defaultPort = 443)
        assertThat(host2).isEqualTo("grpc.cluster.domain.com")
        assertThat(port2).isEqualTo(443)
    }

    @Test
    fun isExpired_evaluatesAccurately() {
        val payload = EnrollmentPayload(
            clusterEndpoint = "192.168.1.50:50051",
            caFingerprint = validSha256Fingerprint,
            pairingToken = "token",
            expiresAtEpochSeconds = 1000L
        )

        assertThat(payload.isExpired(currentEpochSeconds = 999L)).isFalse()
        assertThat(payload.isExpired(currentEpochSeconds = 1000L)).isFalse()
        assertThat(payload.isExpired(currentEpochSeconds = 1001L)).isTrue()

        val nonExpiring = payload.copy(expiresAtEpochSeconds = null)
        assertThat(nonExpiring.isExpired(currentEpochSeconds = 999999999L)).isFalse()
    }

    @Test
    fun toJson_producesCompactValidJson() {
        val payload = EnrollmentPayload(
            clusterEndpoint = "10.0.0.2:50051",
            caFingerprint = validSha256Fingerprint,
            pairingToken = "tok-1",
            nodeId = "node_a",
            expiresAtEpochSeconds = 2000L
        )

        val json = payload.toJson()
        val parsed = EnrollmentPayloadParser.parse(json)

        assertThat(parsed).isEqualTo(payload)
    }
}
