package com.androgrid.worker.enrollment

import com.androgrid.v1.RenewCertificateRequest
import com.androgrid.v1.RenewCertificateResponse
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.ClusterSettingsRepository
import com.androgrid.worker.network.CredentialLifecycleRecord
import com.androgrid.worker.network.CredentialLifecycleState
import com.androgrid.worker.network.MtlsContext
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.math.min

/**
 * Outcome of a coordinated mTLS certificate renewal evaluation/execution cycle.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-005]
 */
sealed class RenewalOutcome {
    object NotDue : RenewalOutcome()
    object BackedOff : RenewalOutcome()
    data class Renewed<T>(
        val newAlias: String,
        val newCertPem: String,
        val notAfterEpochSeconds: Long,
        val trialResult: T
    ) : RenewalOutcome()
    data class Failed(val reason: String, val terminal: Boolean = false) : RenewalOutcome()
}

/**
 * Process-wide serialized manager governing crash-safe mTLS key rotation,
 * pre-activation X.509 validation, generation consumer draining, and startup recovery.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-005]
 */
class CredentialLifecycleManager(
    private val settingsRepository: ClusterSettingsRepository,
    private val keyProvider: KeyEnrollmentProvider = AndroidKeyStoreEnrollmentProvider.createDefault(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val renewalWindowDays: Long = DEFAULT_RENEWAL_WINDOW_DAYS,
    private val onScheduleRenewal: ((delayMs: Long) -> Unit)? = null
) {
    private val mutex = Mutex()
    private val activeConsumers = ConcurrentHashMap<String, AtomicInteger>()
    private val cachedNextEligibleEpochSecs = AtomicLong(0L)

    /**
     * Returns the in-memory cached next eligible renewal timestamp (Unix epoch seconds)
     * so high-frequency sync loops can skip lock/prefs reads while backed off.
     */
    fun getNextEligibleRenewalEpochSeconds(): Long = cachedNextEligibleEpochSecs.get()

    /**
     * Constructs an [MtlsContext] for a candidate [alias] using this manager's configured [keyProvider].
     */
    fun createCandidateMtlsContext(alias: String, caCertPem: String): MtlsContext {
        return keyProvider.createMtlsContext(alias, caCertPem)
    }

    /**
     * Increments the active consumer reference count for [alias] so it cannot be deleted
     * while an old channel or worker is still using it.
     */
    fun acquireGeneration(alias: String) {
        if (alias.isBlank()) return
        activeConsumers.computeIfAbsent(alias) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Decrements the active consumer reference count for [alias] and deletes it if it is
     * currently in [CredentialLifecycleState.RETIRING] with zero remaining consumers.
     */
    suspend fun releaseGeneration(alias: String) {
        if (alias.isBlank()) return
        val count = activeConsumers[alias]?.decrementAndGet()?.coerceAtLeast(0) ?: 0
        if (count == 0) {
            mutex.withLock {
                retireOldAliasIfDrainedLocked()
            }
        }
    }

    /**
     * Reconciles persisted lifecycle state on application startup according to the
     * deterministic recovery table and prunes unreferenced orphaned aliases.
     *
     * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-005]
     */
    suspend fun reconcileOnStartup() = mutex.withLock {
        val settings = settingsRepository.getSettings()
        val record = settingsRepository.getLifecycleRecord()
        val nowMs = clockMs()

        when (record.state) {
            CredentialLifecycleState.ACTIVE -> {
                val active = record.activeAlias
                if (active.isNotBlank() && !keyProvider.hasKey(active)) {
                    // Unrecoverable local key loss
                    settingsRepository.commitSettingsAndLifecycle(
                        settings.copy(caCertPem = "", clientCertPem = "", clientKeyPem = ""),
                        CredentialLifecycleRecord(state = CredentialLifecycleState.ACTIVE)
                    )
                } else {
                    pruneOrphanedAliases(keep = setOf(active))
                    scheduleIfEnrolledLocked(settings, nowMs)
                }
            }

            CredentialLifecycleState.PENDING -> {
                val active = record.activeAlias
                val staging = record.stagingAlias
                if (active.isBlank() || !keyProvider.hasKey(active)) {
                    settingsRepository.commitSettingsAndLifecycle(
                        settings.copy(caCertPem = "", clientCertPem = "", clientKeyPem = ""),
                        CredentialLifecycleRecord(state = CredentialLifecycleState.ACTIVE)
                    )
                    return@withLock
                }
                if (staging.isNotBlank() && !keyProvider.hasKey(staging)) {
                    settingsRepository.commitSettingsAndLifecycle(
                        settings,
                        CredentialLifecycleRecord(
                            state = CredentialLifecycleState.ACTIVE,
                            activeAlias = active
                        )
                    )
                    pruneOrphanedAliases(keep = setOf(active))
                } else {
                    pruneOrphanedAliases(keep = setOf(active, staging))
                }
                scheduleIfEnrolledLocked(settings, nowMs)
            }

            CredentialLifecycleState.CANDIDATE_VERIFYING -> {
                val active = record.activeAlias
                val staging = record.stagingAlias
                if (active.isBlank() || !keyProvider.hasKey(active)) {
                    settingsRepository.commitSettingsAndLifecycle(
                        settings.copy(caCertPem = "", clientCertPem = "", clientKeyPem = ""),
                        CredentialLifecycleRecord(state = CredentialLifecycleState.ACTIVE)
                    )
                    return@withLock
                }
                if (staging.isBlank() || !keyProvider.isEnrolled(staging)) {
                    if (staging.isNotBlank()) keyProvider.deleteKey(staging)
                    settingsRepository.commitSettingsAndLifecycle(
                        settings,
                        CredentialLifecycleRecord(
                            state = CredentialLifecycleState.ACTIVE,
                            activeAlias = active
                        )
                    )
                    pruneOrphanedAliases(keep = setOf(active))
                } else {
                    pruneOrphanedAliases(keep = setOf(active, staging))
                }
                scheduleIfEnrolledLocked(settings, nowMs)
            }

            CredentialLifecycleState.RETIRING -> {
                val newAlias = record.activeAlias
                val oldAlias = record.retiringAlias
                val hasNew = newAlias.isNotBlank() && keyProvider.isEnrolled(newAlias)
                val hasOld = oldAlias.isNotBlank() && keyProvider.hasKey(oldAlias)
                when {
                    hasNew -> {
                        if (hasOld && (activeConsumers[oldAlias]?.get() ?: 0) == 0) {
                            keyProvider.deleteKey(oldAlias)
                        }
                        val nextState = if (hasOld && (activeConsumers[oldAlias]?.get() ?: 0) > 0) {
                            record
                        } else {
                            CredentialLifecycleRecord(
                                state = CredentialLifecycleState.ACTIVE,
                                activeAlias = newAlias
                            )
                        }
                        settingsRepository.commitSettingsAndLifecycle(settings, nextState)
                        pruneOrphanedAliases(keep = setOf(newAlias, nextState.retiringAlias))
                        scheduleIfEnrolledLocked(settings, nowMs)
                    }
                    hasOld -> {
                        // Roll back to old alias if new alias was lost
                        settingsRepository.commitSettingsAndLifecycle(
                            settings.copy(clientKeyPem = "keystore:$oldAlias"),
                            CredentialLifecycleRecord(
                                state = CredentialLifecycleState.ACTIVE,
                                activeAlias = oldAlias
                            )
                        )
                        pruneOrphanedAliases(keep = setOf(oldAlias))
                        scheduleIfEnrolledLocked(settings, nowMs)
                    }
                    else -> {
                        settingsRepository.commitSettingsAndLifecycle(
                            settings.copy(caCertPem = "", clientCertPem = "", clientKeyPem = ""),
                            CredentialLifecycleRecord(state = CredentialLifecycleState.ACTIVE)
                        )
                    }
                }
            }
        }
    }

    /**
     * Checks whether [clientCertPem] is within [renewalWindowDays] of expiration at [nowMs].
     */
    fun isWithinRenewalWindow(clientCertPem: String, nowMs: Long = clockMs()): Boolean {
        val cert = parseSingleCert(clientCertPem) ?: return false
        val remainingMs = cert.notAfter.time - nowMs
        return remainingMs <= renewalWindowDays * MS_PER_DAY
    }

    /**
     * Executes the crash-safe 7-step renewal workflow if the active certificate is inside
     * the renewal window and not in backoff.
     *
     * @param nodeId Expected worker node identifier (`CN`).
     * @param renewRpc Invokes `RenewCertificate` over the currently active mTLS channel.
     * @param trialCandidateRpc Exercises a real operational RPC (`SyncNodeState` or `ClaimCellularTask`)
     *   using the candidate alias before committing activation.
     */
    suspend fun <T> renewIfDue(
        nodeId: String,
        renewRpc: suspend (RenewCertificateRequest) -> Result<RenewCertificateResponse>,
        trialCandidateRpc: suspend (candidateAlias: String, candidateCertPem: String) -> Result<T>
    ): RenewalOutcome = mutex.withLock {
        val settings = settingsRepository.getSettings()
        if (!settings.hasMtlsCredentials) {
            return@withLock RenewalOutcome.NotDue
        }
        val nowMs = clockMs()
        val nowSecs = nowMs / 1000L
        val record = settingsRepository.getLifecycleRecord()

        if (!isWithinRenewalWindow(settings.clientCertPem, nowMs)) {
            scheduleIfEnrolledLocked(settings, nowMs)
            return@withLock RenewalOutcome.NotDue
        }

        if (record.nextEligibleRenewalEpochSeconds > nowSecs) {
            cachedNextEligibleEpochSecs.set(record.nextEligibleRenewalEpochSeconds)
            onScheduleRenewal?.invoke(((record.nextEligibleRenewalEpochSeconds - nowSecs).coerceAtLeast(60L)) * 1000L)
            return@withLock RenewalOutcome.BackedOff
        }

        val activeAlias = record.activeAlias.ifBlank {
            settings.clientKeyPem.removePrefix("keystore:")
                .takeIf { settings.clientKeyPem.startsWith("keystore:") }
                ?: AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        }

        // Resume CANDIDATE_VERIFYING directly at Step 5 without re-signing a new CSR
        val resumedCandidate = if (
            record.state == CredentialLifecycleState.CANDIDATE_VERIFYING &&
            record.stagingAlias.isNotBlank() &&
            record.candidateCertPem.isNotBlank() &&
            keyProvider.isEnrolled(record.stagingAlias)
        ) {
            val spki = keyProvider.getPublicKeyEncoded(record.stagingAlias)
            if (spki != null && validateIssuedCertificate(
                    clientCertPem = record.candidateCertPem,
                    existingCaPem = settings.caCertPem,
                    expectedNodeId = nodeId,
                    expectedSpkiDer = spki,
                    nowMs = nowMs
                ) == null
            ) {
                record.stagingAlias to record.candidateCertPem
            } else {
                keyProvider.deleteKey(record.stagingAlias)
                null
            }
        } else {
            null
        }

        val (stagingAlias, candidateCertPem, notAfterEpochSecs) = if (resumedCandidate != null) {
            val cert = parseSingleCert(resumedCandidate.second)
            val expSecs = (cert?.notAfter?.time ?: nowMs) / 1000L
            Triple(resumedCandidate.first, resumedCandidate.second, expSecs)
        } else {
            // Step 1: Reuse existing PENDING staging key/CSR if present, else create a new staging alias
            val (pendingAlias, csrPem) = if (
                record.state == CredentialLifecycleState.PENDING &&
                record.stagingAlias.isNotBlank() &&
                record.pendingCsrPem.isNotBlank() &&
                keyProvider.hasKey(record.stagingAlias)
            ) {
                record.stagingAlias to record.pendingCsrPem
            } else {
                val newAlias = "${AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS}_$nowMs"
                keyProvider.generateKeyPair(newAlias)
                val generatedCsr = keyProvider.generateCsrPem(newAlias, nodeId)
                val pendingRecord = CredentialLifecycleRecord(
                    state = CredentialLifecycleState.PENDING,
                    activeAlias = activeAlias,
                    stagingAlias = newAlias,
                    pendingCsrPem = generatedCsr,
                    consecutiveFailures = record.consecutiveFailures
                )
                if (!settingsRepository.commitSettingsAndLifecycle(settings, pendingRecord)) {
                    keyProvider.deleteKey(newAlias)
                    return@withLock RenewalOutcome.Failed("Failed to persist PENDING renewal state")
                }
                newAlias to generatedCsr
            }

            // Step 2: Submit CSR over the currently active mTLS channel
            val rpcResult = renewRpc(RenewCertificateRequest.newBuilder().setCsrPem(csrPem).build())
            val response = rpcResult.getOrElse { err ->
                return@withLock handleRpcErrorLocked(settings, activeAlias, pendingAlias, csrPem, record.consecutiveFailures, nowSecs, err)
            }

            // Step 3: Local X.509 profile validation (no trust-anchor swap in v1)
            val stagingSpki = keyProvider.getPublicKeyEncoded(pendingAlias)
                ?: return@withLock abortPendingLocked(
                    settings, activeAlias, pendingAlias, record.consecutiveFailures, nowSecs, "Missing staging public key"
                )
            val validationError = validateIssuedCertificate(
                clientCertPem = response.clientCertPem,
                existingCaPem = settings.caCertPem,
                expectedNodeId = nodeId,
                expectedSpkiDer = stagingSpki,
                nowMs = nowMs
            )
            if (validationError != null) {
                return@withLock abortPendingLocked(
                    settings, activeAlias, pendingAlias, record.consecutiveFailures, nowSecs, validationError
                )
            }

            // Step 4: Install into stagingAlias and persist CANDIDATE_VERIFYING
            keyProvider.installCertificateChain(pendingAlias, response.clientCertPem, settings.caCertPem)
            val verifyingRecord = CredentialLifecycleRecord(
                state = CredentialLifecycleState.CANDIDATE_VERIFYING,
                activeAlias = activeAlias,
                stagingAlias = pendingAlias,
                candidateCertPem = response.clientCertPem,
                consecutiveFailures = record.consecutiveFailures
            )
            if (!settingsRepository.commitSettingsAndLifecycle(settings, verifyingRecord)) {
                return@withLock abortPendingLocked(
                    settings, activeAlias, pendingAlias, record.consecutiveFailures, nowSecs, "Failed to persist CANDIDATE_VERIFYING"
                )
            }
            Triple(pendingAlias, response.clientCertPem, response.notAfterEpochSeconds)
        }

        // Step 5: Trial authentication on a candidate channel using an operational RPC
        val trialResult = trialCandidateRpc(stagingAlias, candidateCertPem)
        val trialValue = trialResult.getOrElse { err ->
            val nextFailures = record.consecutiveFailures + 1
            val nextEpoch = nowSecs + computeBackoffSeconds(nextFailures)
            cachedNextEligibleEpochSecs.set(nextEpoch)
            onScheduleRenewal?.invoke(((nextEpoch - nowSecs).coerceAtLeast(60L)) * 1000L)

            if (isAuthOrTlsRejection(err)) {
                keyProvider.deleteKey(stagingAlias)
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.ACTIVE,
                        activeAlias = activeAlias,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = nextFailures
                    )
                )
            } else {
                // Preserve CANDIDATE_VERIFYING on transient network errors so next retry avoids re-signing
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.CANDIDATE_VERIFYING,
                        activeAlias = activeAlias,
                        stagingAlias = stagingAlias,
                        candidateCertPem = candidateCertPem,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = nextFailures
                    )
                )
            }
            return@withLock RenewalOutcome.Failed("Candidate trial RPC failed: ${err.message}")
        }

        // Step 6: Atomically commit RETIRING + updated ClusterSettings (retaining existing caCertPem)
        val newSettings = settings.copy(
            clientCertPem = candidateCertPem,
            clientKeyPem = "keystore:$stagingAlias"
        )
        val retiringRecord = CredentialLifecycleRecord(
            state = CredentialLifecycleState.RETIRING,
            activeAlias = stagingAlias,
            retiringAlias = activeAlias
        )
        if (!settingsRepository.commitSettingsAndLifecycle(newSettings, retiringRecord)) {
            return@withLock RenewalOutcome.Failed("Failed to commit RETIRING credential activation")
        }
        cachedNextEligibleEpochSecs.set(0L)

        // Step 7: Delete retiring alias only if zero consumers remain
        retireOldAliasIfDrainedLocked()
        scheduleIfEnrolledLocked(newSettings, nowMs)

        RenewalOutcome.Renewed(
            newAlias = stagingAlias,
            newCertPem = candidateCertPem,
            notAfterEpochSeconds = notAfterEpochSecs,
            trialResult = trialValue
        )
    }

    private fun retireOldAliasIfDrainedLocked() {
        val record = settingsRepository.getLifecycleRecord()
        if (record.state != CredentialLifecycleState.RETIRING) return
        val oldAlias = record.retiringAlias
        if (oldAlias.isNotBlank() && (activeConsumers[oldAlias]?.get() ?: 0) > 0) {
            return
        }
        if (oldAlias.isNotBlank()) {
            keyProvider.deleteKey(oldAlias)
        }
        val settings = settingsRepository.getSettings()
        settingsRepository.commitSettingsAndLifecycle(
            settings,
            CredentialLifecycleRecord(
                state = CredentialLifecycleState.ACTIVE,
                activeAlias = record.activeAlias
            )
        )
    }

    private fun abortPendingLocked(
        settings: ClusterSettings,
        activeAlias: String,
        stagingAlias: String,
        prevFailures: Int,
        nowSecs: Long,
        reason: String
    ): RenewalOutcome.Failed {
        keyProvider.deleteKey(stagingAlias)
        val failures = prevFailures + 1
        val nextEpoch = nowSecs + computeBackoffSeconds(failures)
        cachedNextEligibleEpochSecs.set(nextEpoch)
        onScheduleRenewal?.invoke(((nextEpoch - nowSecs).coerceAtLeast(60L)) * 1000L)
        settingsRepository.commitSettingsAndLifecycle(
            settings,
            CredentialLifecycleRecord(
                state = CredentialLifecycleState.ACTIVE,
                activeAlias = activeAlias,
                nextEligibleRenewalEpochSeconds = nextEpoch,
                consecutiveFailures = failures
            )
        )
        return RenewalOutcome.Failed(reason)
    }

    private fun handleRpcErrorLocked(
        settings: ClusterSettings,
        activeAlias: String,
        stagingAlias: String,
        csrPem: String,
        prevFailures: Int,
        nowSecs: Long,
        err: Throwable
    ): RenewalOutcome.Failed {
        val (status, trailers) = extractGrpcStatus(err)
        val failures = prevFailures + 1

        return when (status?.code) {
            Status.Code.INVALID_ARGUMENT -> {
                // Bad CSR: discard staging key so next attempt generates a fresh one with backoff
                abortPendingLocked(
                    settings, activeAlias, stagingAlias, prevFailures, nowSecs, "CSR rejected: ${status.description}"
                )
            }
            Status.Code.UNIMPLEMENTED,
            Status.Code.PERMISSION_DENIED -> {
                val nextEpoch = nowSecs + TWENTY_FOUR_HOURS_SECS
                cachedNextEligibleEpochSecs.set(nextEpoch)
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.PENDING,
                        activeAlias = activeAlias,
                        stagingAlias = stagingAlias,
                        pendingCsrPem = csrPem,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = failures
                    )
                )
                RenewalOutcome.Failed("Renewal blocked (${status.code}): ${status.description}", terminal = true)
            }
            Status.Code.FAILED_PRECONDITION -> {
                val serverNext = parseEpochHeader(trailers, HEADER_NEXT_RENEWAL_EPOCH)
                val nextEpoch = serverNext ?: (nowSecs + TWENTY_FOUR_HOURS_SECS)
                cachedNextEligibleEpochSecs.set(nextEpoch)
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.PENDING,
                        activeAlias = activeAlias,
                        stagingAlias = stagingAlias,
                        pendingCsrPem = csrPem,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = failures
                    )
                )
                onScheduleRenewal?.invoke(((nextEpoch - nowSecs).coerceAtLeast(60L)) * 1000L)
                RenewalOutcome.Failed("Renewal precondition failed: ${status.description}")
            }
            Status.Code.RESOURCE_EXHAUSTED -> {
                val retryAfter = parseEpochHeader(trailers, HEADER_RETRY_AFTER_EPOCH)
                val nextEpoch = retryAfter ?: (nowSecs + computeBackoffSeconds(failures))
                cachedNextEligibleEpochSecs.set(nextEpoch)
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.PENDING,
                        activeAlias = activeAlias,
                        stagingAlias = stagingAlias,
                        pendingCsrPem = csrPem,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = failures
                    )
                )
                onScheduleRenewal?.invoke(((nextEpoch - nowSecs).coerceAtLeast(60L)) * 1000L)
                RenewalOutcome.Failed("Renewal rate limited: ${status.description}")
            }
            else -> {
                val nextEpoch = nowSecs + computeBackoffSeconds(failures)
                cachedNextEligibleEpochSecs.set(nextEpoch)
                settingsRepository.commitSettingsAndLifecycle(
                    settings,
                    CredentialLifecycleRecord(
                        state = CredentialLifecycleState.PENDING,
                        activeAlias = activeAlias,
                        stagingAlias = stagingAlias,
                        pendingCsrPem = csrPem,
                        nextEligibleRenewalEpochSeconds = nextEpoch,
                        consecutiveFailures = failures
                    )
                )
                onScheduleRenewal?.invoke(((nextEpoch - nowSecs).coerceAtLeast(60L)) * 1000L)
                RenewalOutcome.Failed("Transient renewal failure: ${err.message}")
            }
        }
    }

    private fun scheduleIfEnrolledLocked(settings: ClusterSettings, nowMs: Long) {
        if (!settings.hasMtlsCredentials) return
        val cert = parseSingleCert(settings.clientCertPem) ?: return
        val targetMs = cert.notAfter.time - renewalWindowDays * MS_PER_DAY
        val delayMs = (targetMs - nowMs).coerceAtLeast(0L)
        onScheduleRenewal?.invoke(delayMs)
    }

    private fun pruneOrphanedAliases(keep: Set<String>) {
        val prefix = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        keyProvider.listAliases(prefix)
            .filter { it !in keep }
            .forEach { keyProvider.deleteKey(it) }
    }

    companion object {
        const val DEFAULT_RENEWAL_WINDOW_DAYS: Long = 30L
        private const val MS_PER_DAY: Long = 86_400_000L
        private const val TWENTY_FOUR_HOURS_SECS: Long = 86_400L
        private const val CLIENT_AUTH_EKU_OID: String = "1.3.6.1.5.5.7.3.2"
        private const val HEADER_NEXT_RENEWAL_EPOCH: String = "x-androgrid-next-renewal-epoch"
        private const val HEADER_RETRY_AFTER_EPOCH: String = "x-androgrid-retry-after-epoch"

        /**
         * Validates an issued X.509 client certificate against the expected public key,
         * Subject CN/OU, X.509 v3 profile (`CA:FALSE`, `DigitalSignature`, `ClientAuth`, ECDSA P-256),
         * validity window, and existing enrolled CA trust anchor.
         *
         * @return `null` if valid, or a descriptive error reason string otherwise.
         */
        fun validateIssuedCertificate(
            clientCertPem: String,
            existingCaPem: String,
            expectedNodeId: String,
            expectedSpkiDer: ByteArray,
            nowMs: Long
        ): String? {
            val leaf = parseSingleCert(clientCertPem)
                ?: return "Issued client certificate PEM is invalid"
            val ca = parseSingleCert(existingCaPem)
                ?: return "Enrolled CA trust anchor PEM is invalid"

            if (!leaf.publicKey.encoded.contentEquals(expectedSpkiDer)) {
                return "Issued certificate public key does not match pending staging keypair"
            }

            val ecPub = leaf.publicKey as? ECPublicKey
                ?: return "Issued certificate public key is not ECDSA"
            if (ecPub.params.order.bitLength() != 256) {
                return "Issued certificate ECDSA key is not P-256"
            }

            if (leaf.basicConstraints != -1) {
                return "Issued certificate must not be a CA (CA:FALSE required)"
            }

            val keyUsage = leaf.keyUsage
            if (keyUsage == null || keyUsage.isEmpty() || !keyUsage[0]) {
                return "Issued certificate missing DigitalSignature KeyUsage"
            }

            val eku = runCatching { leaf.extendedKeyUsage }.getOrNull()
            if (eku == null || !eku.contains(CLIENT_AUTH_EKU_OID)) {
                return "Issued certificate missing ClientAuth ExtendedKeyUsage"
            }

            val x500Name = X500Name.getInstance(leaf.subjectX500Principal.encoded)
            val cnValues = x500Name.getRDNs(BCStyle.CN).flatMap { rdn ->
                rdn.typesAndValues.map { IETFUtils.valueToString(it.value) }
            }
            if (cnValues.size != 1 || cnValues[0] != expectedNodeId) {
                return "Issued certificate CN mismatch (expected $expectedNodeId, got $cnValues)"
            }
            val ouValues = x500Name.getRDNs(BCStyle.OU).flatMap { rdn ->
                rdn.typesAndValues.map { IETFUtils.valueToString(it.value) }
            }
            if (ouValues.size != 1 || ouValues[0] != "Edge Compute") {
                return "Issued certificate OU mismatch (expected Edge Compute, got $ouValues)"
            }

            if (nowMs < leaf.notBefore.time || nowMs >= leaf.notAfter.time) {
                return "Issued certificate is outside its validity period"
            }

            try {
                leaf.verify(ca.publicKey)
            } catch (e: Exception) {
                return "Issued certificate does not chain to existing trust anchor: ${e.message}"
            }

            return null
        }

        private fun parseSingleCert(pem: String): X509Certificate? {
            if (pem.isBlank()) return null
            return runCatching {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))) as X509Certificate
            }.getOrNull()
        }

        private fun computeBackoffSeconds(failures: Int): Long {
            val shift = min(failures.coerceAtLeast(1), 10)
            return min(60L shl (shift - 1), TWENTY_FOUR_HOURS_SECS)
        }

        private fun extractGrpcStatus(err: Throwable): Pair<Status?, Metadata?> {
            return when (err) {
                is StatusException -> err.status to err.trailers
                is StatusRuntimeException -> err.status to err.trailers
                else -> null to null
            }
        }

        private fun isAuthOrTlsRejection(err: Throwable): Boolean {
            val (status, _) = extractGrpcStatus(err)
            if (status?.code == Status.Code.UNAUTHENTICATED || status?.code == Status.Code.PERMISSION_DENIED) {
                return true
            }
            var cur: Throwable? = err
            while (cur != null) {
                if (cur is SSLException || cur is CertificateException) return true
                cur = cur.cause
            }
            return false
        }

        private fun parseEpochHeader(trailers: Metadata?, name: String): Long? {
            if (trailers == null) return null
            val key = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)
            return trailers.get(key)?.toLongOrNull()
        }
    }
}
