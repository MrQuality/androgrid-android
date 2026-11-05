package com.androgrid.worker.network

/**
 * Immutable configuration data model storing dynamic network endpoints and mTLS credentials.
 *
 * Traces to: [INV-001], [INV-002], [REQ-OP-001], [REQ-OP-003], [NFR-SEC-001]
 *
 * @property wifiHost Orchestrator host or IP on the local Wi-Fi network.
 * @property wifiPort Orchestrator gRPC port on the local Wi-Fi network.
 * @property cellularHost Orchestrator domain name for cellular WAN Anycast ingress.
 * @property cellularPort Ingress port for cellular WAN (typically 443).
 * @property caCertPem PEM-encoded root Certificate Authority (CA) certificate.
 * @property clientCertPem PEM-encoded client X.509 certificate chain.
 * @property clientKeyPem PEM-encoded client private key (PKCS#8).
 */
data class ClusterSettings(
    val wifiHost: String = DEFAULT_WIFI_HOST,
    val wifiPort: Int = DEFAULT_WIFI_PORT,
    val cellularHost: String = DEFAULT_CELLULAR_HOST,
    val cellularPort: Int = DEFAULT_CELLULAR_PORT,
    val caCertPem: String = "",
    val clientCertPem: String = "",
    val clientKeyPem: String = ""
) {
    companion object {
        /** Default Wi-Fi host for local cluster testing. */
        const val DEFAULT_WIFI_HOST: String = "192.168.1.100"

        /** Default Wi-Fi port for local orchestrator. */
        const val DEFAULT_WIFI_PORT: Int = 50051

        /** Default cellular ingress domain for Cloudflare Anycast tunnel. */
        const val DEFAULT_CELLULAR_HOST: String = "grpc.cluster.domain.com"

        /** Default cellular ingress port. */
        const val DEFAULT_CELLULAR_PORT: Int = 443
    }

    /**
     * Checks if all required Mutual TLS (mTLS) credentials are configured.
     *
     * Traces to: [INV-002], [NFR-SEC-001]
     */
    val hasMtlsCredentials: Boolean
        get() = caCertPem.isNotBlank() && clientCertPem.isNotBlank() && clientKeyPem.isNotBlank()
}
