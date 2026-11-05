package com.androgrid.worker.network

import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.ClaimCellularTaskResponse
import com.androgrid.v1.SubmitCellularResultRequest
import com.androgrid.v1.SubmitCellularResultResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.contract.ContractConstants
import com.google.common.truth.Truth.assertThat
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for zero-keepalive cellular lifecycle and baseband radio-sleep teardown.
 *
 * Traces to: [INV-001], [INV-002], [INV-003], [ANTI-001], [NFR-POW-001], [NFR-SEC-001]
 */
class CellularRadioLifecycleTest {

    private class RecordingChannel : ManagedChannel() {
        val shutdownNowCalled = AtomicBoolean(false)
        val awaitTerminationCalled = AtomicBoolean(false)
        val awaitTerminationTimeoutMs = AtomicLong(0L)

        override fun shutdown(): ManagedChannel = this

        override fun shutdownNow(): ManagedChannel {
            shutdownNowCalled.set(true)
            return this
        }

        override fun isShutdown(): Boolean = shutdownNowCalled.get()

        override fun isTerminated(): Boolean = shutdownNowCalled.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            awaitTerminationCalled.set(true)
            awaitTerminationTimeoutMs.set(unit.toMillis(timeout))
            return true
        }

        override fun authority(): String = "localhost:443"

        override fun <RequestT : Any?, ResponseT : Any?> newCall(
            methodDescriptor: io.grpc.MethodDescriptor<RequestT, ResponseT>?,
            callOptions: io.grpc.CallOptions?
        ): io.grpc.ClientCall<RequestT, ResponseT> {
            throw UnsupportedOperationException("Not required for mock channel lifecycle tests")
        }
    }

    private class FakeUnaryService(
        private val claimResult: Result<ClaimCellularTaskResponse> = Result.success(
            ClaimCellularTaskResponse.newBuilder()
                .setTaskAvailable(true)
                .setTask(TaskSpec.newBuilder().setTaskId("task_test_01").build())
                .build()
        ),
        private val submitResult: Result<SubmitCellularResultResponse> = Result.success(
            SubmitCellularResultResponse.newBuilder()
                .setAccepted(true)
                .setStatusMessage("committed")
                .build()
        )
    ) : CellularRpcInvoker {
        override suspend fun invokeClaim(
            channel: ManagedChannel,
            request: ClaimCellularTaskRequest
        ): ClaimCellularTaskResponse {
            return claimResult.getOrThrow()
        }

        override suspend fun invokeSubmit(
            channel: ManagedChannel,
            request: SubmitCellularResultRequest
        ): SubmitCellularResultResponse {
            return submitResult.getOrThrow()
        }
    }

    @Test
    fun testClaimCellularTask_immediatelyTerminatesChannelWithin200Ms() = runTest {
        // Enforces [INV-003] and [NFR-POW-001]: Channel must be shutdownNow() and awaitTermination(<= 200ms)
        val channel = RecordingChannel()
        val client = CellularTransportClient(rpcInvoker = FakeUnaryService())

        val request = ClaimCellularTaskRequest.newBuilder()
            .setNodeId("node_test_01")
            .setBatteryLevelPct(85)
            .setIsCharging(true)
            .build()

        val response = client.claimTaskWithChannel(channel, request)

        assertThat(response.isSuccess).isTrue()
        assertThat(channel.shutdownNowCalled.get()).isTrue()
        assertThat(channel.awaitTerminationCalled.get()).isTrue()
        assertThat(channel.awaitTerminationTimeoutMs.get())
            .isAtMost(ContractConstants.RADIO_SLEEP_TERMINATION_DEADLINE_MS)
    }

    @Test
    fun testClaimCellularTask_terminatesChannelOnRpcFailure() = runTest {
        // Enforces [INV-003]: Even on network error, socket must immediately close
        val channel = RecordingChannel()
        val failingInvoker = FakeUnaryService(
            claimResult = Result.failure(StatusRuntimeException(Status.UNAVAILABLE))
        )
        val client = CellularTransportClient(rpcInvoker = failingInvoker)

        val request = ClaimCellularTaskRequest.newBuilder()
            .setNodeId("node_test_01")
            .setBatteryLevelPct(85)
            .setIsCharging(true)
            .build()

        val response = client.claimTaskWithChannel(channel, request)

        assertThat(response.isFailure).isTrue()
        assertThat(channel.shutdownNowCalled.get()).isTrue()
        assertThat(channel.awaitTerminationCalled.get()).isTrue()
    }

    @Test
    fun testSubmitCellularResult_immediatelyTerminatesChannelWithin200Ms() = runTest {
        // Enforces [INV-003] and [NFR-POW-001]: Submission socket must close within 200 ms
        val channel = RecordingChannel()
        val client = CellularTransportClient(rpcInvoker = FakeUnaryService())

        val request = SubmitCellularResultRequest.newBuilder()
            .setNodeId("node_test_01")
            .setTaskId("task_test_01")
            .setRunAttempt(1)
            .build()

        val response = client.submitResultWithChannel(channel, request)

        assertThat(response.isSuccess).isTrue()
        assertThat(channel.shutdownNowCalled.get()).isTrue()
        assertThat(channel.awaitTerminationCalled.get()).isTrue()
        assertThat(channel.awaitTerminationTimeoutMs.get())
            .isAtMost(ContractConstants.RADIO_SLEEP_TERMINATION_DEADLINE_MS)
    }

    @Test
    fun testSubmitCellularResult_terminatesChannelOnPreconditionFailure() = runTest {
        // Enforces [INV-003] and [INV-004]: Preemption or stale attempt error still tears down channel
        val channel = RecordingChannel()
        val staleInvoker = FakeUnaryService(
            submitResult = Result.failure(
                StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("TASK_ATTEMPT_STALE"))
            )
        )
        val client = CellularTransportClient(rpcInvoker = staleInvoker)

        val request = SubmitCellularResultRequest.newBuilder()
            .setNodeId("node_test_01")
            .setTaskId("task_test_01")
            .setRunAttempt(1)
            .build()

        val response = client.submitResultWithChannel(channel, request)

        assertThat(response.isFailure).isTrue()
        assertThat(channel.shutdownNowCalled.get()).isTrue()
        assertThat(channel.awaitTerminationCalled.get()).isTrue()
    }

    @Test
    fun testCellularTransportClient_acceptsCustomSslSocketFactory() {
        val mtlsContext = DefaultMtlsSocketFactoryProvider.createMtlsContextFromPem(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
            clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
        )
        val client = CellularTransportClient(
            ingressHost = "grpc.cloudflare.net",
            ingressPort = 443,
            sslSocketFactory = mtlsContext.socketFactory
        )
        assertThat(client).isNotNull()
    }
}
