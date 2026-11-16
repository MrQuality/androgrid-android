package com.androgrid.worker.enrollment

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.androgrid.v1.RenewCertificateResponse
import com.androgrid.worker.network.CellularRpcInvoker
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.CredentialLifecycleRecord
import com.androgrid.worker.network.CredentialLifecycleState
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.service.CertificateRenewalWorker
import com.google.common.truth.Truth.assertThat
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.PEMParser
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class CredentialLifecycleManagerTest {

    private lateinit var caKeyPair: KeyPair
    private lateinit var caCert: X509Certificate
    private lateinit var caCertPem: String

    @Before
    fun setUp() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }
        caKeyPair = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val caName = X500Name("CN=AndroGrid Test Root CA")
        val builder = JcaX509v3CertificateBuilder(
            caName,
            BigInteger.valueOf(1L),
            Date(now - 60_000L),
            Date(now + 3650L * 86_400_000L),
            caName,
            caKeyPair.public
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(caKeyPair.private)
        caCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        caCertPem = toPem(caCert)
    }

    private fun toPem(obj: Any): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        return sw.toString()
    }

    private fun signWorkerCert(
        publicKey: PublicKey,
        cn: String,
        ou: String = "Edge Compute",
        daysValid: Long = 365L,
        isCa: Boolean = false,
        includeClientAuth: Boolean = true,
        signingCaKey: PrivateKey = caKeyPair.private,
        signingCaName: X500Name = X500Name("CN=AndroGrid Test Root CA")
    ): String {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=$cn, O=AndroGrid Worker, OU=$ou")
        val builder = JcaX509v3CertificateBuilder(
            signingCaName,
            BigInteger.valueOf(now + System.nanoTime()),
            Date(now - 60_000L),
            Date(now + daysValid * 86_400_000L),
            subject,
            publicKey
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        if (includeClientAuth) {
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(signingCaKey)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return toPem(cert)
    }

    private fun extractPublicKeyFromCsr(csrPem: String): PublicKey {
        val parser = PEMParser(StringReader(csrPem))
        val csr = parser.readObject() as org.bouncycastle.pkcs.PKCS10CertificationRequest
        return JcaPEMKeyConverter().getPublicKey(csr.subjectPublicKeyInfo)
    }

    @Test
    fun `outside renewal window returns NotDue and schedules future WorkManager delay`() = runTest {
        val keyProvider = SoftwareKeyEnrollmentProvider()
        val oldAlias = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        val kp = keyProvider.generateKeyPair(oldAlias)
        val certPem = signWorkerCert(kp.public, "worker-01", daysValid = 90L)
        keyProvider.installCertificateChain(oldAlias, certPem, caCertPem)

        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(
                caCertPem = caCertPem,
                clientCertPem = certPem,
                clientKeyPem = "keystore:$oldAlias"
            )
        )
        var scheduledDelayMs: Long? = null
        val manager = CredentialLifecycleManager(
            settingsRepository = repo,
            keyProvider = keyProvider,
            onScheduleRenewal = { scheduledDelayMs = it }
        )

        val outcome = manager.renewIfDue<Unit>(
            nodeId = "worker-01",
            renewRpc = { error("Must not call RenewCertificate outside window") },
            trialCandidateRpc = { _, _ -> error("Must not trial outside window") }
        )

        assertThat(outcome).isEqualTo(RenewalOutcome.NotDue)
        assertThat(scheduledDelayMs).isGreaterThan(50L * 86_400_000L)
    }

    @Test
    fun `7-step renewal preserves retiring alias until active consumer drains`() = runTest {
        val keyProvider = SoftwareKeyEnrollmentProvider()
        val oldAlias = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        val oldKp = keyProvider.generateKeyPair(oldAlias)
        val oldCertPem = signWorkerCert(oldKp.public, "worker-01", daysValid = 10L)
        keyProvider.installCertificateChain(oldAlias, oldCertPem, caCertPem)

        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(
                caCertPem = caCertPem,
                clientCertPem = oldCertPem,
                clientKeyPem = "keystore:$oldAlias"
            )
        )
        repo.commitSettingsAndLifecycle(
            repo.getSettings(),
            CredentialLifecycleRecord(state = CredentialLifecycleState.ACTIVE, activeAlias = oldAlias)
        )

        val manager = CredentialLifecycleManager(settingsRepository = repo, keyProvider = keyProvider)
        manager.acquireGeneration(oldAlias)

        val outcome = manager.renewIfDue(
            nodeId = "worker-01",
            renewRpc = { req ->
                val newPub = extractPublicKeyFromCsr(req.csrPem)
                val newCert = signWorkerCert(newPub, "worker-01", daysValid = 365L)
                Result.success(
                    RenewCertificateResponse.newBuilder()
                        .setClientCertPem(newCert)
                        .setCaCertPem(caCertPem)
                        .setNotAfterEpochSeconds((System.currentTimeMillis() / 1000L) + 365L * 86400L)
                        .build()
                )
            },
            trialCandidateRpc = { candidateAlias, _ ->
                assertThat(keyProvider.isEnrolled(candidateAlias)).isTrue()
                Result.success("ok")
            }
        )

        assertThat(outcome).isInstanceOf(RenewalOutcome.Renewed::class.java)
        val renewed = outcome as RenewalOutcome.Renewed<*>
        assertThat(repo.getSettings().clientKeyPem).isEqualTo("keystore:${renewed.newAlias}")

        // Old alias MUST still exist while consumer holds generation!
        assertThat(keyProvider.hasKey(oldAlias)).isTrue()
        assertThat(repo.getLifecycleRecord().state).isEqualTo(CredentialLifecycleState.RETIRING)

        // Release consumer -> old alias deleted and state transitions to ACTIVE
        manager.releaseGeneration(oldAlias)
        assertThat(keyProvider.hasKey(oldAlias)).isFalse()
        assertThat(repo.getLifecycleRecord().state).isEqualTo(CredentialLifecycleState.ACTIVE)
        assertThat(repo.getLifecycleRecord().activeAlias).isEqualTo(renewed.newAlias)
    }

    @Test
    fun `rejects trust anchor swap and invalid cert profile in v1`() = runTest {
        val keyProvider = SoftwareKeyEnrollmentProvider()
        val oldAlias = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        val oldKp = keyProvider.generateKeyPair(oldAlias)
        val oldCertPem = signWorkerCert(oldKp.public, "worker-01", daysValid = 10L)
        keyProvider.installCertificateChain(oldAlias, oldCertPem, caCertPem)

        val rogueCaKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(
                caCertPem = caCertPem,
                clientCertPem = oldCertPem,
                clientKeyPem = "keystore:$oldAlias"
            )
        )
        val manager = CredentialLifecycleManager(settingsRepository = repo, keyProvider = keyProvider)

        val outcome = manager.renewIfDue(
            nodeId = "worker-01",
            renewRpc = { req ->
                val newPub = extractPublicKeyFromCsr(req.csrPem)
                // Signed by rogue CA instead of enrolled CA
                val badCert = signWorkerCert(newPub, "worker-01", signingCaKey = rogueCaKey.private)
                Result.success(
                    RenewCertificateResponse.newBuilder()
                        .setClientCertPem(badCert)
                        .setCaCertPem(caCertPem)
                        .build()
                )
            },
            trialCandidateRpc = { _, _ -> Result.success(Unit) }
        )

        assertThat(outcome).isInstanceOf(RenewalOutcome.Failed::class.java)
        assertThat(repo.getSettings().clientKeyPem).isEqualTo("keystore:$oldAlias")
        assertThat(repo.getLifecycleRecord().state).isEqualTo(CredentialLifecycleState.ACTIVE)
    }

    @Test
    fun `startup reconciliation recovers PENDING, CANDIDATE_VERIFYING, and RETIRING states`() = runTest {
        val keyProvider = SoftwareKeyEnrollmentProvider()
        val oldAlias = "androgrid_worker_mtls_key_old"
        val newAlias = "androgrid_worker_mtls_key_new"
        val oldKp = keyProvider.generateKeyPair(oldAlias)
        val oldCert = signWorkerCert(oldKp.public, "worker-01", daysValid = 10L)
        keyProvider.installCertificateChain(oldAlias, oldCert, caCertPem)

        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(caCertPem = caCertPem, clientCertPem = oldCert, clientKeyPem = "keystore:$newAlias")
        )
        // RETIRING where newAlias was lost on crash -> rolls back to oldAlias
        repo.commitSettingsAndLifecycle(
            repo.getSettings(),
            CredentialLifecycleRecord(
                state = CredentialLifecycleState.RETIRING,
                activeAlias = newAlias,
                retiringAlias = oldAlias
            )
        )
        val manager = CredentialLifecycleManager(settingsRepository = repo, keyProvider = keyProvider)
        manager.reconcileOnStartup()
        assertThat(repo.getSettings().clientKeyPem).isEqualTo("keystore:$oldAlias")
        assertThat(repo.getLifecycleRecord().state).isEqualTo(CredentialLifecycleState.ACTIVE)

        // PENDING where stagingAlias is missing -> reverts to ACTIVE(oldAlias)
        repo.commitSettingsAndLifecycle(
            repo.getSettings(),
            CredentialLifecycleRecord(
                state = CredentialLifecycleState.PENDING,
                activeAlias = oldAlias,
                stagingAlias = "missing_staging",
                pendingCsrPem = "csr"
            )
        )
        manager.reconcileOnStartup()
        assertThat(repo.getLifecycleRecord().state).isEqualTo(CredentialLifecycleState.ACTIVE)
        assertThat(repo.getLifecycleRecord().activeAlias).isEqualTo(oldAlias)
    }

    @Test
    fun `error matrix handles FAILED_PRECONDITION metadata and terminal PERMISSION_DENIED`() = runTest {
        val keyProvider = SoftwareKeyEnrollmentProvider()
        val oldAlias = AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
        val kp = keyProvider.generateKeyPair(oldAlias)
        val certPem = signWorkerCert(kp.public, "worker-01", daysValid = 10L)
        keyProvider.installCertificateChain(oldAlias, certPem, caCertPem)

        val repo = InMemoryClusterSettingsRepository(
            ClusterSettings(caCertPem = caCertPem, clientCertPem = certPem, clientKeyPem = "keystore:$oldAlias")
        )
        val fixedNowMs = System.currentTimeMillis()
        val fixedNowSecs = fixedNowMs / 1000L
        var rescheduledMs: Long? = null
        val manager = CredentialLifecycleManager(
            settingsRepository = repo,
            keyProvider = keyProvider,
            clockMs = { fixedNowMs },
            onScheduleRenewal = { rescheduledMs = it }
        )

        val trailers = Metadata().apply {
            put(
                Metadata.Key.of("x-androgrid-next-renewal-epoch", Metadata.ASCII_STRING_MARSHALLER),
                (fixedNowSecs + 3600L).toString()
            )
        }
        val precondOutcome = manager.renewIfDue(
            nodeId = "worker-01",
            renewRpc = {
                Result.failure(StatusException(Status.FAILED_PRECONDITION.withDescription("OUTSIDE_RENEWAL_WINDOW"), trailers))
            },
            trialCandidateRpc = { _, _ -> Result.success(Unit) }
        )
        assertThat(precondOutcome).isInstanceOf(RenewalOutcome.Failed::class.java)
        assertThat((precondOutcome as RenewalOutcome.Failed).terminal).isFalse()
        assertThat(rescheduledMs).isEqualTo(3_600_000L)

        // Subsequent call during backoff returns BackedOff without invoking RPC
        val backedOff = manager.renewIfDue(
            nodeId = "worker-01",
            renewRpc = { error("Must not invoke RPC during backoff") },
            trialCandidateRpc = { _, _ -> Result.success(Unit) }
        )
        assertThat(backedOff).isEqualTo(RenewalOutcome.BackedOff)
    }

    @Test
    fun `cellular renewCertificate tears down ephemeral channel immediately`() = runTest {
        val mockChannel = mockk<ManagedChannel>(relaxed = true)
        every { mockChannel.awaitTermination(any(), any()) } returns true
        val invoker = mockk<CellularRpcInvoker>()
        coEvery { invoker.invokeRenewCertificate(mockChannel, any()) } returns
            RenewCertificateResponse.getDefaultInstance()

        val client = CellularTransportClient(rpcInvoker = invoker)
        val result = client.renewCertificateWithChannel(
            mockChannel,
            com.androgrid.v1.RenewCertificateRequest.newBuilder().setCsrPem("csr").build()
        )

        assertThat(result.isSuccess).isTrue()
        verify { mockChannel.shutdownNow() }
        verify { mockChannel.awaitTermination(200L, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `WorkManager CertificateRenewalWorker schedules unique connected work with KEEP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        CertificateRenewalWorker.schedule(context, 60_000L)
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CertificateRenewalWorker.WORK_NAME)
            .get()
        assertThat(workInfos).hasSize(1)
        assertThat(workInfos[0].state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(workInfos[0].constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
