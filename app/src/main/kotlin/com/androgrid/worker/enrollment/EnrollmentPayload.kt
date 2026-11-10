package com.androgrid.worker.enrollment

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * Immutable data structure representing scanned or transferred enrollment credentials.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 *
 * @property clusterEndpoint Control plane gRPC or HTTP enrollment host and port (e.g. "192.168.1.100:50051").
 * @property caFingerprint Lowercase hexadecimal SHA-256 fingerprint of the cluster Root CA certificate.
 * @property pairingToken Single-use cryptographic ephemeral token issued by CLI or operator.
 * @property nodeId Optional pre-assigned node identifier.
 * @property expiresAtEpochSeconds Optional token expiration timestamp in Unix epoch seconds.
 */
data class EnrollmentPayload(
    val clusterEndpoint: String,
    val caFingerprint: String,
    val pairingToken: String,
    val nodeId: String? = null,
    val expiresAtEpochSeconds: Long? = null
) {
    init {
        require(clusterEndpoint.isNotBlank()) { "cluster_endpoint must not be blank" }
        require(pairingToken.isNotBlank()) { "pairing_token must not be blank" }
        require(caFingerprint.length == 64 && caFingerprint.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "ca_fingerprint must be a valid 64-character hexadecimal SHA-256 hash"
        }
    }

    /**
     * Resolves host and port components from clusterEndpoint.
     *
     * @param defaultPort Default port if unspecified in endpoint string (defaults to 50051).
     * @return Pair containing the host string and integer port.
     */
    fun parseEndpoint(defaultPort: Int = 50051): Pair<String, Int> {
        val trimmed = clusterEndpoint.trim()
        val colonIndex = trimmed.lastIndexOf(':')
        return if (colonIndex > 0 && colonIndex < trimmed.length - 1) {
            val host = trimmed.substring(0, colonIndex).removePrefix("[").removeSuffix("]")
            val port = trimmed.substring(colonIndex + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(trimmed, defaultPort)
        }
    }

    /**
     * Checks if this pairing token has passed its expiration deadline.
     *
     * @param currentEpochSeconds Current Unix timestamp in seconds.
     * @return True if expired, false otherwise.
     */
    fun isExpired(currentEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val exp = expiresAtEpochSeconds ?: return false
        return currentEpochSeconds > exp
    }

    /**
     * Serializes this payload to a compact JSON string.
     *
     * @return Compact JSON representation matching backend pairing payload specification.
     */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("cluster_endpoint", clusterEndpoint)
        obj.put("ca_fingerprint", caFingerprint.lowercase())
        obj.put("pairing_token", pairingToken)
        nodeId?.let { obj.put("node_id", it) }
        expiresAtEpochSeconds?.let { obj.put("expires_at_epoch_s", it) }
        return obj.toString()
    }
}

/**
 * Parser transforming QR code string content (JSON or URI) into verified [EnrollmentPayload].
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
object EnrollmentPayloadParser {

    private const val ANDROGRID_SCHEME = "androgrid"
    private const val ENROLL_HOST = "enroll"

    /**
     * Parses raw QR code string into validated [EnrollmentPayload].
     *
     * @param rawContent Scanned text content from QR code.
     * @return Validated [EnrollmentPayload] instance.
     * @throws IllegalArgumentException If payload format is malformed or required fields are missing.
     */
    fun parse(rawContent: String): EnrollmentPayload {
        val trimmed = rawContent.trim()
        require(trimmed.isNotEmpty()) { "QR content must not be empty" }

        return if (trimmed.startsWith("{")) {
            parseJson(trimmed)
        } else if (trimmed.startsWith("$ANDROGRID_SCHEME://", ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            // Attempt JSON fallback first
            try {
                parseJson(trimmed)
            } catch (_: Exception) {
                throw IllegalArgumentException("Unsupported QR code payload format: expected JSON or androgrid:// URI")
            }
        }
    }

    private fun parseJson(jsonStr: String): EnrollmentPayload {
        try {
            val obj = JSONObject(jsonStr)

            val endpoint = obj.optString("cluster_endpoint", "").trim()
            val ca = obj.optString("ca_fingerprint", "").trim().lowercase()
            val token = obj.optString("pairing_token", "").trim()

            require(endpoint.isNotEmpty()) { "cluster_endpoint is required" }
            require(ca.isNotEmpty()) { "ca_fingerprint is required" }
            require(token.isNotEmpty()) { "pairing_token is required" }

            val nodeId = if (obj.has("node_id") && !obj.isNull("node_id")) {
                obj.getString("node_id").trim().takeIf { it.isNotEmpty() }
            } else null

            val expires = if (obj.has("expires_at_epoch_s") && !obj.isNull("expires_at_epoch_s")) {
                obj.getLong("expires_at_epoch_s")
            } else if (obj.has("expires_at_epoch_seconds") && !obj.isNull("expires_at_epoch_seconds")) {
                obj.getLong("expires_at_epoch_seconds")
            } else null

            return EnrollmentPayload(
                clusterEndpoint = endpoint,
                caFingerprint = ca,
                pairingToken = token,
                nodeId = nodeId,
                expiresAtEpochSeconds = expires
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed JSON pairing payload: ${e.message}", e)
        }
    }

    private fun parseUri(uriStr: String): EnrollmentPayload {
        try {
            val uri = URI(uriStr)
            require(uri.scheme.equals(ANDROGRID_SCHEME, ignoreCase = true)) {
                "Invalid URI scheme: ${uri.scheme}"
            }

            val queryMap = mutableMapOf<String, String>()
            uri.rawQuery?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                    val value = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    queryMap[key] = value
                }
            }

            val endpoint = queryMap["endpoint"] ?: queryMap["cluster_endpoint"] ?: ""
            val token = queryMap["token"] ?: queryMap["pairing_token"] ?: ""
            val ca = (queryMap["ca"] ?: queryMap["ca_fingerprint"] ?: "").lowercase()
            val nodeId = queryMap["node"] ?: queryMap["node_id"]
            val expires = (queryMap["expires"] ?: queryMap["expires_at_epoch_s"])?.toLongOrNull()

            require(endpoint.isNotBlank()) { "URI query must contain endpoint" }
            require(token.isNotBlank()) { "URI query must contain token" }
            require(ca.isNotBlank()) { "URI query must contain ca" }

            return EnrollmentPayload(
                clusterEndpoint = endpoint,
                caFingerprint = ca,
                pairingToken = token,
                nodeId = nodeId,
                expiresAtEpochSeconds = expires
            )
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Malformed enrollment URI: ${e.message}", e)
        }
    }
}
