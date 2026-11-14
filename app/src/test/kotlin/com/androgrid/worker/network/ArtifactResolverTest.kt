package com.androgrid.worker.network

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.FetchArtifactRequest
import com.androgrid.v1.FetchArtifactResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.contract.ContractConstants
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ArtifactResolver] and [ArtifactMemoryCache].
 *
 * Traces to: [INV-002], [INV-006], [NFR-PERF-001], [REQ-VER-003]
 */
class ArtifactResolverTest {

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun resolve_inlineBytecode_returnsReadyWithoutCallingRpc() = runTest {
        val cache = ArtifactMemoryCache()
        val resolver = ArtifactResolver(cache)
        val rpcCalls = AtomicInteger(0)
        val task = TaskSpec.newBuilder()
            .setTaskId("t_inline")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6d)))
            .build()

        val result = resolver.resolve(task) {
            rpcCalls.incrementAndGet()
            FetchArtifactResponse.getDefaultInstance()
        }

        assertThat(result).isInstanceOf(ArtifactResolution.Ready::class.java)
        assertThat((result as ArtifactResolution.Ready).task.wasmBytecode.toByteArray())
            .isEqualTo(byteArrayOf(0x00, 0x61, 0x73, 0x6d))
        assertThat(rpcCalls.get()).isEqualTo(0)
    }

    @Test
    fun resolve_cacheMissThenCacheHit_fetchesOnceAndCachesInMemory() = runTest {
        val cache = ArtifactMemoryCache()
        val resolver = ArtifactResolver(cache)
        val payload = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val sha = sha256Hex(payload)
        val rpcCalls = AtomicInteger(0)

        val task = TaskSpec.newBuilder()
            .setTaskId("t_ext")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(sha)
            .build()

        val first = resolver.resolve(task) { req ->
            rpcCalls.incrementAndGet()
            assertThat(req.artifactSha256).isEqualTo(sha)
            FetchArtifactResponse.newBuilder()
                .setWasmBytecode(ByteString.copyFrom(payload))
                .build()
        }

        assertThat(first).isInstanceOf(ArtifactResolution.Ready::class.java)
        assertThat((first as ArtifactResolution.Ready).task.wasmBytecode.toByteArray()).isEqualTo(payload)
        assertThat(rpcCalls.get()).isEqualTo(1)
        assertThat(cache.currentBytes()).isEqualTo(payload.size)

        val second = resolver.resolve(task) {
            rpcCalls.incrementAndGet()
            FetchArtifactResponse.getDefaultInstance()
        }

        assertThat(second).isInstanceOf(ArtifactResolution.Ready::class.java)
        assertThat((second as ArtifactResolution.Ready).task.wasmBytecode.toByteArray()).isEqualTo(payload)
        assertThat(rpcCalls.get()).isEqualTo(1)
    }

    @Test
    fun resolve_invalidSha256_returnsPayloadCorrupt() = runTest {
        val resolver = ArtifactResolver(ArtifactMemoryCache())
        val task = TaskSpec.newBuilder()
            .setTaskId("t_bad_sha")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256("not-a-sha")
            .build()

        val result = resolver.resolve(task) {
            throw AssertionError("RPC should not be called for malformed SHA-256")
        }

        assertThat(result).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((result as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_CORRUPT")
    }

    @Test
    fun resolve_digestMismatch_returnsPayloadCorruptAndDoesNotCache() = runTest {
        val cache = ArtifactMemoryCache()
        val resolver = ArtifactResolver(cache)
        val expectedSha = sha256Hex("expected".toByteArray())
        val task = TaskSpec.newBuilder()
            .setTaskId("t_mismatch")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(expectedSha)
            .build()

        val result = resolver.resolve(task) {
            FetchArtifactResponse.newBuilder()
                .setWasmBytecode(ByteString.copyFromUtf8("tampered"))
                .build()
        }

        assertThat(result).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((result as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_CORRUPT")
        assertThat(cache.currentBytes()).isEqualTo(0)
    }

    @Test
    fun resolve_payloadExceeds16Mb_returnsPayloadTooLarge() = runTest {
        val resolver = ArtifactResolver(ArtifactMemoryCache())
        val oversized = ByteArray(ContractConstants.MAX_FRAME_SIZE_BYTES.toInt() + 1)
        val task = TaskSpec.newBuilder()
            .setTaskId("t_big")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(sha256Hex(oversized))
            .build()

        val result = resolver.resolve(task) {
            FetchArtifactResponse.newBuilder()
                .setWasmBytecode(ByteString.copyFrom(oversized))
                .build()
        }

        assertThat(result).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((result as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_TOO_LARGE")
    }

    @Test
    fun resolve_grpcStatusMapping_distinguishesTerminalAndTransientErrors() = runTest {
        val resolver = ArtifactResolver(ArtifactMemoryCache())
        val sha = sha256Hex("dummy".toByteArray())
        val task = TaskSpec.newBuilder()
            .setTaskId("t_grpc")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(sha)
            .build()

        val notFound = resolver.resolve(task) {
            throw StatusRuntimeException(Status.NOT_FOUND.withDescription("missing"))
        }
        assertThat(notFound).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((notFound as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("ARTIFACT_NOT_FOUND")

        val resourceExhausted = resolver.resolve(task) {
            throw StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("too large"))
        }
        assertThat(resourceExhausted).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((resourceExhausted as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_TOO_LARGE")

        val unavailable = resolver.resolve(task) {
            throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("transient"))
        }
        assertThat(unavailable).isInstanceOf(ArtifactResolution.TransientError::class.java)
    }

    @Test
    fun memoryCache_clear_evictsAllEntries() {
        val cache = ArtifactMemoryCache(maxSizeBytes = 1024)
        val bytes = ByteString.copyFromUtf8("hello-wasm")
        cache.put("a".repeat(64), bytes)
        assertThat(cache.currentBytes()).isEqualTo(bytes.size())
        assertThat(cache.get("a".repeat(64))).isEqualTo(bytes)

        cache.clear()
        assertThat(cache.currentBytes()).isEqualTo(0)
        assertThat(cache.get("a".repeat(64))).isNull()
    }
}
