package com.androgrid.worker.network

import com.androgrid.v1.AndroGridServiceGrpcKt
import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.ClaimCellularTaskResponse
import com.androgrid.v1.FetchArtifactRequest
import com.androgrid.v1.FetchArtifactResponse
import com.androgrid.v1.SubmitCellularResultRequest
import com.androgrid.v1.SubmitCellularResultResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.contract.ContractConstants
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Interface abstracting gRPC stub invocation for hermetic testing.
 *
 * Traces to: [INV-003], [REQ-OP-003], [REQ-OP-004]
 */
interface CellularRpcInvoker {
    /**
     * Executes unary ClaimCellularTask RPC.
     */
    suspend fun invokeClaim(
        channel: ManagedChannel,
        request: ClaimCellularTaskRequest
    ): ClaimCellularTaskResponse

    /**
     * Executes unary SubmitCellularResult RPC.
     */
    suspend fun invokeSubmit(
        channel: ManagedChannel,
        request: SubmitCellularResultRequest
    ): SubmitCellularResultResponse

    /**
     * Executes unary FetchArtifact RPC over the authenticated mTLS channel.
     */
    suspend fun invokeFetchArtifact(
        channel: ManagedChannel,
        request: FetchArtifactRequest
    ): FetchArtifactResponse {
        throw UnsupportedOperationException("invokeFetchArtifact is not implemented")
    }
}

/**
 * Production implementation invoking AndroGridServiceCoroutineStub over OkHttp.
 *
 * Traces to: [REQ-OP-003], [REQ-OP-004]
 */
class DefaultCellularRpcInvoker : CellularRpcInvoker {
    override suspend fun invokeClaim(
        channel: ManagedChannel,
        request: ClaimCellularTaskRequest
    ): ClaimCellularTaskResponse {
        val stub = AndroGridServiceGrpcKt.AndroGridServiceCoroutineStub(channel)
        return stub.claimCellularTask(request)
    }

    override suspend fun invokeSubmit(
        channel: ManagedChannel,
        request: SubmitCellularResultRequest
    ): SubmitCellularResultResponse {
        val stub = AndroGridServiceGrpcKt.AndroGridServiceCoroutineStub(channel)
        return stub.submitCellularResult(request)
    }

    override suspend fun invokeFetchArtifact(
        channel: ManagedChannel,
        request: FetchArtifactRequest
    ): FetchArtifactResponse {
        val stub = AndroGridServiceGrpcKt.AndroGridServiceCoroutineStub(channel)
        return stub.fetchArtifact(request)
    }
}

/**
 * Outcome of claiming a cellular task and resolving any decoupled WASM payload
 * within a single ephemeral mTLS channel session before radio sleep teardown.
 *
 * Traces to: [INV-002], [INV-003], [NFR-POW-001], [REQ-OP-003]
 */
sealed class CellularClaimOutcome {
    object NoTask : CellularClaimOutcome()
    data class TaskReady(val task: TaskSpec) : CellularClaimOutcome()
    data class TerminalArtifactFailure(
        val task: TaskSpec,
        val errorCode: String,
        val errorMessage: String,
        val submissionSucceeded: Boolean
    ) : CellularClaimOutcome()
    data class TransientFailure(val cause: Throwable) : CellularClaimOutcome()
}

/**
 * Zero-keepalive cellular transport client for metered WAN operations.
 * Implements disconnected claim and atomic result submission with mandatory radio-sleep teardown.
 *
 * Traces to: [INV-001], [INV-003], [ANTI-001], [NFR-POW-001], [NFR-PERF-001], [REQ-OP-003], [REQ-OP-004]
 */
class CellularTransportClient(
    private val ingressHost: String = "grpc.cluster.domain.com",
    private val ingressPort: Int = 443,
    private val rpcInvoker: CellularRpcInvoker = DefaultCellularRpcInvoker(),
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val artifactResolver: ArtifactResolver = ArtifactResolver()
) {

    /**
     * Creates an ephemeral ManagedChannel configured for Cloudflare Anycast ingress.
     * Enforces TLS 1.3 transport security and 16 MB max frame size.
     *
     * Traces to: [INV-002], [NFR-SEC-001], [NFR-PERF-001]
     */
    fun createChannel(): ManagedChannel {
        val socketFactory = checkNotNull(sslSocketFactory) {
            "Mutual TLS SSLSocketFactory must be configured for CellularTransportClient per [INV-002]"
        }
        val builder = OkHttpChannelBuilder.forAddress(ingressHost, ingressPort)
            .useTransportSecurity()
            .sslSocketFactory(socketFactory)
            .tlsConnectionSpec(
                MtlsContext.TLS_13_VERSIONS,
                MtlsContext.TLS_13_CIPHERS
            )
            .maxInboundMessageSize(ContractConstants.MAX_FRAME_SIZE_BYTES.toInt())
        return builder.build()
    }

    /**
     * Performs disconnected task claim over cellular WAN.
     * Automatically and unconditionally tears down socket within 200 ms upon completion.
     *
     * Traces to: [REQ-OP-003], [INV-003], [NFR-POW-001]
     *
     * @param request claim parameters including hardware capabilities and battery level.
     * @return Result containing response or caught exception.
     */
    suspend fun claimTask(request: ClaimCellularTaskRequest): Result<ClaimCellularTaskResponse> {
        val channel = try {
            createChannel()
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        return claimTaskWithChannel(channel, request)
    }

    /**
     * Internal claim implementation over a provided channel.
     * Enforces radio sleep socket termination in finally block.
     */
    suspend fun claimTaskWithChannel(
        channel: ManagedChannel,
        request: ClaimCellularTaskRequest
    ): Result<ClaimCellularTaskResponse> {
        return try {
            val response = rpcInvoker.invokeClaim(channel, request)
            Result.success(response)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            teardownChannelForRadioSleep(channel)
        }
    }

    /**
     * Claims a cellular task and resolves any decoupled WASM artifact on the same open
     * mTLS channel before radio-sleep teardown. On terminal fetch errors (e.g. NOT_FOUND),
     * immediately submits a failed [SubmitCellularResultRequest] on the same channel so the
     * orchestrator lease is not left dangling.
     *
     * Traces to: [INV-002], [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-OP-004]
     */
    suspend fun claimAndResolveTask(request: ClaimCellularTaskRequest): CellularClaimOutcome {
        val channel = try {
            createChannel()
        } catch (e: Throwable) {
            return CellularClaimOutcome.TransientFailure(e)
        }
        return claimAndResolveTaskWithChannel(channel, request)
    }

    /**
     * Single-channel claim + FetchArtifact resolution with radio-sleep teardown in `finally`.
     */
    suspend fun claimAndResolveTaskWithChannel(
        channel: ManagedChannel,
        request: ClaimCellularTaskRequest
    ): CellularClaimOutcome {
        return try {
            val claimResponse = rpcInvoker.invokeClaim(channel, request)
            if (!claimResponse.taskAvailable) {
                return CellularClaimOutcome.NoTask
            }
            val claimedTask = claimResponse.task
            when (
                val resolution = artifactResolver.resolve(claimedTask) { fetchReq ->
                    rpcInvoker.invokeFetchArtifact(channel, fetchReq)
                }
            ) {
                is ArtifactResolution.Ready -> CellularClaimOutcome.TaskReady(resolution.task)
                is ArtifactResolution.TerminalFailure -> {
                    val failReport = SubmitCellularResultRequest.newBuilder()
                        .setNodeId(request.nodeId)
                        .setTaskId(claimedTask.taskId)
                        .setRunAttempt(claimedTask.runAttempt)
                        .setExitCode(-1)
                        .setErrorCode(resolution.errorCode)
                        .setStderrBytes(ByteString.copyFromUtf8(resolution.errorMessage))
                        .setExecutionDurationMs(0)
                        .build()
                    val submitted = try {
                        rpcInvoker.invokeSubmit(channel, failReport)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    CellularClaimOutcome.TerminalArtifactFailure(
                        task = claimedTask,
                        errorCode = resolution.errorCode,
                        errorMessage = resolution.errorMessage,
                        submissionSucceeded = submitted
                    )
                }
                is ArtifactResolution.TransientError ->
                    CellularClaimOutcome.TransientFailure(resolution.cause)
            }
        } catch (e: Throwable) {
            CellularClaimOutcome.TransientFailure(e)
        } finally {
            teardownChannelForRadioSleep(channel)
        }
    }

    /**
     * Performs atomic task result submission over a newly established ephemeral connection.
     * Automatically and unconditionally tears down socket within 200 ms upon completion.
     *
     * Traces to: [REQ-OP-004], [INV-003], [NFR-POW-001]
     *
     * @param request result payload including run_attempt, stdout, stderr, and exit code.
     * @return Result containing response or caught exception.
     */
    suspend fun submitResult(request: SubmitCellularResultRequest): Result<SubmitCellularResultResponse> {
        val channel = try {
            createChannel()
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        return submitResultWithChannel(channel, request)
    }

    /**
     * Internal submission implementation over a provided channel.
     * Enforces radio sleep socket termination in finally block.
     */
    suspend fun submitResultWithChannel(
        channel: ManagedChannel,
        request: SubmitCellularResultRequest
    ): Result<SubmitCellularResultResponse> {
        return try {
            val response = rpcInvoker.invokeSubmit(channel, request)
            Result.success(response)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            teardownChannelForRadioSleep(channel)
        }
    }

    /**
     * Strictly enforces socket teardown within 200 ms to enable RRC baseband radio sleep.
     *
     * Traces to: [INV-003], [NFR-POW-001]
     */
    private suspend fun teardownChannelForRadioSleep(channel: ManagedChannel) {
        channel.shutdownNow()
        withContext(Dispatchers.IO) {
            try {
                channel.awaitTermination(
                    ContractConstants.RADIO_SLEEP_TERMINATION_DEADLINE_MS,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
