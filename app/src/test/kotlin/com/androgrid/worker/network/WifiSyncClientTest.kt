package com.androgrid.worker.network

import com.androgrid.v1.RegisterNodeRequest
import com.androgrid.v1.RegisterNodeResponse
import com.androgrid.v1.SyncNodeStateRequest
import com.androgrid.v1.SyncNodeStateResponse
import com.google.common.truth.Truth.assertThat
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for Wi-Fi declarative state synchronization client.
 *
 * Traces to: [REQ-OP-001], [REQ-OP-002], [INV-002], [NFR-SEC-001], Section 6.1
 */
class WifiSyncClientTest {

    private class MockWifiChannel : ManagedChannel() {
        val shutdownCalled = AtomicBoolean(false)
        val terminated = AtomicBoolean(false)

        override fun shutdown(): ManagedChannel {
            shutdownCalled.set(true)
            terminated.set(true)
            return this
        }

        override fun shutdownNow(): ManagedChannel {
            shutdownCalled.set(true)
            terminated.set(true)
            return this
        }

        override fun isShutdown(): Boolean = shutdownCalled.get()

        override fun isTerminated(): Boolean = terminated.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

        override fun authority(): String = "192.168.1.100:50051"

        override fun <RequestT : Any?, ResponseT : Any?> newCall(
            methodDescriptor: io.grpc.MethodDescriptor<RequestT, ResponseT>?,
            callOptions: io.grpc.CallOptions?
        ): io.grpc.ClientCall<RequestT, ResponseT> {
            throw UnsupportedOperationException("Mock channel")
        }
    }

    private class FakeWifiInvoker(
        private val registerResult: Result<RegisterNodeResponse> = Result.success(
            RegisterNodeResponse.newBuilder()
                .setSessionToken("sess_abc_123")
                .setHeartbeatIntervalSeconds(2)
                .setLeaseTimeoutSeconds(10)
                .build()
        ),
        private val syncResult: Result<SyncNodeStateResponse> = Result.success(
            SyncNodeStateResponse.newBuilder()
                .setServerEpochMs(1710000000000L)
                .build()
        )
    ) : WifiRpcInvoker {
        override suspend fun invokeRegister(
            channel: ManagedChannel,
            request: RegisterNodeRequest
        ): RegisterNodeResponse = registerResult.getOrThrow()

        override suspend fun invokeSync(
            channel: ManagedChannel,
            request: SyncNodeStateRequest
        ): SyncNodeStateResponse = syncResult.getOrThrow()

        override suspend fun invokeFetchArtifact(
            channel: ManagedChannel,
            request: com.androgrid.v1.FetchArtifactRequest
        ): com.androgrid.v1.FetchArtifactResponse {
            return com.androgrid.v1.FetchArtifactResponse.getDefaultInstance()
        }
    }

    @Test
    fun testRegisterNode_success() = runTest {
        val mockChannel = MockWifiChannel()
        val client = WifiSyncClient(
            host = "192.168.1.100",
            port = 50051,
            rpcInvoker = FakeWifiInvoker(),
            channelProvider = { _, _ -> mockChannel }
        )

        val request = RegisterNodeRequest.newBuilder()
            .setNodeId("worker_wifi_01")
            .build()

        val result = client.registerNode(request)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.sessionToken).isEqualTo("sess_abc_123")
    }

    @Test
    fun testSyncNodeState_success() = runTest {
        val mockChannel = MockWifiChannel()
        val client = WifiSyncClient(
            host = "192.168.1.100",
            port = 50051,
            rpcInvoker = FakeWifiInvoker(),
            channelProvider = { _, _ -> mockChannel }
        )

        val request = SyncNodeStateRequest.newBuilder()
            .setNodeId("worker_wifi_01")
            .setSessionToken("sess_abc_123")
            .build()

        val result = client.syncNodeState(request)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.serverEpochMs).isEqualTo(1710000000000L)
    }

    @Test
    fun testRegisterNode_rpcFailureHandled() = runTest {
        val mockChannel = MockWifiChannel()
        val failureInvoker = FakeWifiInvoker(
            registerResult = Result.failure(StatusRuntimeException(Status.UNAVAILABLE))
        )
        val client = WifiSyncClient(
            host = "192.168.1.100",
            port = 50051,
            rpcInvoker = failureInvoker,
            channelProvider = { _, _ -> mockChannel }
        )

        val request = RegisterNodeRequest.newBuilder().setNodeId("worker_wifi_01").build()
        val result = client.registerNode(request)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun testShutdown_closesChannel() = runTest {
        val mockChannel = MockWifiChannel()
        val client = WifiSyncClient(
            host = "192.168.1.100",
            port = 50051,
            rpcInvoker = FakeWifiInvoker(),
            channelProvider = { _, _ -> mockChannel }
        )

        client.shutdown(timeoutMs = 100L)
        assertThat(mockChannel.shutdownCalled.get()).isTrue()
    }

    @Test
    fun testWifiSyncClient_acceptsCustomSslSocketFactory() {
        val mtlsContext = DefaultMtlsSocketFactoryProvider.createMtlsContextFromPem(
            caCertPem = TestCertificateFixtures.CA_CERT_PEM,
            clientCertPem = TestCertificateFixtures.CLIENT_CERT_PEM,
            clientKeyPem = TestCertificateFixtures.clientPrivateKeyPem
        )
        val client = WifiSyncClient(
            host = "10.0.0.1",
            port = 50051,
            sslSocketFactory = mtlsContext.socketFactory
        )
        assertThat(client).isNotNull()
    }

    @Test
    fun testWifiSyncClient_rejectsChannelCreationWithoutSslSocketFactory() = runTest {
        val client = WifiSyncClient(
            host = "10.0.0.1",
            port = 50051
        )
        val request = RegisterNodeRequest.newBuilder().setNodeId("worker-1").build()
        val result = client.registerNode(request)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("Mutual TLS SSLSocketFactory must be configured")
    }
}
