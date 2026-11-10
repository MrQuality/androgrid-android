package com.androgrid.worker.enrollment

import com.androgrid.worker.network.ClusterSettingsRepository

/**
 * High-level coordinator managing on-device key pair generation, CSR creation,
 * bootstrap enrollment RPC execution, and certificate persistence.
 *
 * Traces to: [INV-001], [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class EnrollmentCoordinator(
    private val keyProvider: KeyEnrollmentProvider = AndroidKeyStoreEnrollmentProvider.createDefault(),
    private val enrollmentClient: BootstrapEnrollmentClient = GrpcBootstrapEnrollmentClient(),
    private val settingsRepository: ClusterSettingsRepository,
    private val keyAlias: String = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
) {

    /**
     * Executes the zero-friction enrollment workflow:
     * 1. Validates token expiration.
     * 2. Resolves effective node identifier.
     * 3. Generates on-device NIST P-256 key pair anchored in hardware KeyStore.
     * 4. Constructs PKCS#10 CSR with Common Name set to node identifier.
     * 5. Exchanges CSR and pairing token with orchestrator over pinned TLS.
     * 6. Installs returned certificate chain into KeyStore and updates repository settings.
     *
     * @param payload Scanned or entered [EnrollmentPayload].
     * @param defaultNodeId Fallback node identifier if not specified in payload.
     * @return [EnrollmentResult] indicating success or failure reason.
     */
    suspend fun executeEnrollment(
        payload: EnrollmentPayload,
        defaultNodeId: String
    ): EnrollmentResult {
        if (payload.isExpired()) {
            return EnrollmentResult.Failure("Pairing token has expired before enrollment initiation")
        }

        val effectiveNodeId = payload.nodeId?.takeIf { it.isNotBlank() } ?: defaultNodeId

        // Step 1: Generate on-device key pair inside KeyStore
        try {
            keyProvider.generateKeyPair(keyAlias)
        } catch (e: Exception) {
            return EnrollmentResult.Failure("Failed to generate on-device key pair: ${e.message}")
        }

        // Step 2: Construct PKCS#10 CSR
        val csrPem = try {
            keyProvider.generateCsrPem(keyAlias, effectiveNodeId)
        } catch (e: Exception) {
            keyProvider.deleteKey(keyAlias)
            return EnrollmentResult.Failure("Failed to construct PKCS#10 CSR: ${e.message}")
        }

        // Step 3: Connect to orchestrator and redeem token
        val result = enrollmentClient.enroll(payload, csrPem, effectiveNodeId)

        return when (result) {
            is EnrollmentResult.Success -> {
                try {
                    // Step 4: Install issued certificate chain into KeyStore
                    keyProvider.installCertificateChain(keyAlias, result.clientCertPem, result.caCertPem)

                    // Step 5: Update cluster settings repository
                    val (host, port) = payload.parseEndpoint()
                    if (port == 443) {
                        settingsRepository.updateCellularEndpoint(host, port)
                    } else {
                        settingsRepository.updateWifiEndpoint(host, port)
                    }

                    // For clientKeyPem, anchor it to KeyStore alias
                    settingsRepository.updateCertificates(
                        caCertPem = result.caCertPem,
                        clientCertPem = result.clientCertPem,
                        clientKeyPem = "keystore:$keyAlias"
                    )

                    EnrollmentResult.Success(result.clientCertPem, result.caCertPem)
                } catch (e: Exception) {
                    keyProvider.deleteKey(keyAlias)
                    EnrollmentResult.Failure("Failed to persist enrolled certificate credentials: ${e.message}")
                }
            }
            is EnrollmentResult.Failure -> {
                keyProvider.deleteKey(keyAlias)
                result
            }
        }
    }
}
