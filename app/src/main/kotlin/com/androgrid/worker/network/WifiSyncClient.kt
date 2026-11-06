package com.androgrid.worker.network

import com.androgrid.v1.AndroGridServiceGrpcKt
import com.androgrid.v1.RegisterNodeRequest
import com.androgrid.v1.RegisterNodeResponse
import com.androgrid.v1.SyncNodeStateRequest
import com.androgrid.v1.SyncNodeStateResponse
import com.androgrid.worker.contract.ContractConstants
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Interface abstracting Wi-Fi declarative gRPC stub invocation for testing.
 *
 * Traces to: [REQ-OP-001], [REQ-OP-002]
 */
interface WifiRpcInvoker {
    /**
     * Executes RegisterNode RPC.
     */
    suspend fun invokeRegister(
        channel: ManagedChannel,
        request: RegisterNodeRequest
    ): RegisterNodeResponse

    /**
     * Executes SyncNodeState RPC.
     */
    suspend fun invokeSync(
        channel: ManagedChannel,
        request: SyncNodeStateRequest
    ): SyncNodeStateResponse
}

/**
 * Production implementation invoking AndroGridServiceCoroutineStub over persistent Wi-Fi channel.
 *
 * Traces to: [REQ-OP-001], [REQ-OP-002]
 */
class DefaultWifiRpcInvoker : WifiRpcInvoker {
    override suspend fun invokeRegister(
        channel: ManagedChannel,
        request: RegisterNodeRequest
    ): RegisterNodeResponse {
        val stub = AndroGridServiceGrpcKt.AndroGridServiceCoroutineStub(channel)
        return stub.registerNode(request)
    }

    override suspend fun invokeSync(
        channel: ManagedChannel,
        request: SyncNodeStateRequest
    ): SyncNodeStateResponse {
        val stub = AndroGridServiceGrpcKt.AndroGridServiceCoroutineStub(channel)
        return stub.syncNodeState(request)
    }
}

/**
 * Wi-Fi LAN declarative state synchronization client.
 * Maintains persistent outbound connection to host orchestrator during local Wi-Fi association.
 *
 * Traces to: Section 6.1, [INV-001], [INV-002], [NFR-SEC-001], [NFR-PERF-001], [REQ-OP-001], [REQ-OP-002]
 */
class WifiSyncClient(
    private val host: String,
    private val port: Int,
    private val rpcInvoker: WifiRpcInvoker = DefaultWifiRpcInvoker(),
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val authority: String? = null,
    private val channelProvider: ((String, Int) -> ManagedChannel)? = null
) {
    private val channel: ManagedChannel by lazy {
        channelProvider?.invoke(host, port) ?: run {
            val builder = OkHttpChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .maxInboundMessageSize(ContractConstants.MAX_FRAME_SIZE_BYTES.toInt())
            if (sslSocketFactory != null) {
                builder.sslSocketFactory(sslSocketFactory)
            }
            if (authority != null) {
                builder.overrideAuthority(authority)
            }
            builder.build()
        }
    }

    /**
     * Invokes RegisterNode to establish session token and negotiation parameters.
     *
     * Traces to: [REQ-OP-001]
     */
    suspend fun registerNode(request: RegisterNodeRequest): Result<RegisterNodeResponse> {
        return try {
            val response = rpcInvoker.invokeRegister(channel, request)
            Result.success(response)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Invokes SyncNodeState to transmit active task progress and receive assignments.
     *
     * Traces to: [REQ-OP-002]
     */
    suspend fun syncNodeState(request: SyncNodeStateRequest): Result<SyncNodeStateResponse> {
        return try {
            val response = rpcInvoker.invokeSync(channel, request)
            Result.success(response)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Shuts down channel cleanly when disconnecting from Wi-Fi LAN.
     */
    suspend fun shutdown(timeoutMs: Long = 1000L) {
        channel.shutdown()
        withContext(Dispatchers.IO) {
            try {
                channel.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
