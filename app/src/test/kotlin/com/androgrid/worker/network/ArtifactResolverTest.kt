package com.androgrid.worker.network

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.FetchArtifactResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.contract.ContractConstants
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ArtifactResolver] and [ArtifactMemoryCache].
 *
 * Traces to: [INV-002], [INV-006], [NFR-PERF-001], [REQ-VER-003]
 */
class ArtifactResolverTest {

    private val testNodeId = "pixel8-worker-01"

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

        val result = resolver.resolve(task, testNodeId) {
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

        val first = resolver.resolve(task, testNodeId) { req ->
            rpcCalls.incrementAndGet()
            assertThat(req.artifactSha256).isEqualTo(sha)
            assertThat(req.nodeId).isEqualTo(testNodeId)
            FetchArtifactResponse.newBuilder()
                .setWasmBytecode(ByteString.copyFrom(payload))
                .build()
        }

        assertThat(first).isInstanceOf(ArtifactResolution.Ready::class.java)
        assertThat((first as ArtifactResolution.Ready).task.wasmBytecode.toByteArray()).isEqualTo(payload)
        assertThat(rpcCalls.get()).isEqualTo(1)
        assertThat(cache.currentBytes()).isEqualTo(payload.size)

        val second = resolver.resolve(task, testNodeId) {
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

        val result = resolver.resolve(task, testNodeId) {
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

        val result = resolver.resolve(task, testNodeId) {
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

        val result = resolver.resolve(task, testNodeId) {
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

        val notFound = resolver.resolve(task, testNodeId) {
            throw StatusRuntimeException(Status.NOT_FOUND.withDescription("missing"))
        }
        assertThat(notFound).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((notFound as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("ARTIFACT_NOT_FOUND")

        val resourceExhausted = resolver.resolve(task, testNodeId) {
            throw StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("too large"))
        }
        assertThat(resourceExhausted).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((resourceExhausted as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_TOO_LARGE")

        val unimplemented = resolver.resolve(task, testNodeId) {
            throw StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("old server"))
        }
        assertThat(unimplemented).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((unimplemented as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("RPC_UNIMPLEMENTED")

        val permissionDenied = resolver.resolve(task, testNodeId) {
            throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("cn mismatch"))
        }
        assertThat(permissionDenied).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
        assertThat((permissionDenied as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("AUTH_REJECTED")

        val unavailable = resolver.resolve(task, testNodeId) {
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

    @Test
    fun okHttpBlobFetcher_preAllocatedDownload_cachesVerifiedPayloadAndSkipsRpc() = runTest {
        val server = MockWebServer()
        val payload = ByteArray(16 * 1024) { (it and 0xFF).toByte() }
        val sha = sha256Hex(payload)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Artifact-Size", payload.size.toString())
                .setBody(Buffer().write(payload))
        )
        server.start()

        val client = OkHttpClient()
        try {
            val cache = ArtifactMemoryCache()
            val resolver = ArtifactResolver(cache)
            val rpcCalls = AtomicInteger(0)
            val task = TaskSpec.newBuilder()
                .setTaskId("t_http_ok")
                .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
                .setArtifactSha256(sha)
                .build()

            val result = resolver.resolve(
                task = task,
                nodeId = testNodeId,
                blobFetcher = { targetSha ->
                    OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$targetSha").toString())
                }
            ) {
                rpcCalls.incrementAndGet()
                FetchArtifactResponse.getDefaultInstance()
            }

            assertThat(result).isInstanceOf(ArtifactResolution.Ready::class.java)
            assertThat((result as ArtifactResolution.Ready).task.wasmBytecode.toByteArray()).isEqualTo(payload)
            assertThat(rpcCalls.get()).isEqualTo(0)
            assertThat(cache.currentBytes()).isEqualTo(payload.size)
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun okHttpBlobFetcher_chunkedWithoutContentLength_succeedsWhenVerified() = runTest {
        val server = MockWebServer()
        val payload = "chunked-wasm-module".toByteArray()
        val sha = sha256Hex(payload)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setChunkedBody(Buffer().write(payload), 5)
        )
        server.start()

        val client = OkHttpClient()
        try {
            val resolver = ArtifactResolver(ArtifactMemoryCache())
            val task = TaskSpec.newBuilder()
                .setTaskId("t_http_chunked")
                .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
                .setArtifactSha256(sha)
                .build()

            val result = resolver.resolve(
                task = task,
                nodeId = testNodeId,
                blobFetcher = { targetSha ->
                    OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$targetSha").toString())
                }
            ) {
                FetchArtifactResponse.getDefaultInstance()
            }

            assertThat(result).isInstanceOf(ArtifactResolution.Ready::class.java)
            assertThat((result as ArtifactResolution.Ready).task.wasmBytecode.toByteArray()).isEqualTo(payload)
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun okHttpBlobFetcher_truncatedStream_returnsPayloadCorrupt() = runTest {
        val server = MockWebServer()
        val fullPayload = ByteArray(100) { 0x42 }
        val sha = sha256Hex(fullPayload)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Artifact-Size", "100")
                .setBody(Buffer().write(ByteArray(50) { 0x42 }))
        )
        server.start()

        val client = OkHttpClient()
        try {
            val resolver = ArtifactResolver(ArtifactMemoryCache())
            val task = TaskSpec.newBuilder()
                .setTaskId("t_http_trunc")
                .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
                .setArtifactSha256(sha)
                .build()

            val result = resolver.resolve(
                task = task,
                nodeId = testNodeId,
                blobFetcher = { targetSha ->
                    OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$targetSha").toString())
                }
            ) {
                FetchArtifactResponse.getDefaultInstance()
            }

            assertThat(result).isInstanceOf(ArtifactResolution.TerminalFailure::class.java)
            assertThat((result as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_CORRUPT")
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun okHttpBlobFetcher_httpStatusMapping_mapsTerminalAndTransientErrors() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(413))
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()

        val client = OkHttpClient()
        try {
            val resolver = ArtifactResolver(ArtifactMemoryCache())
            val sha = sha256Hex("dummy".toByteArray())
            val task = TaskSpec.newBuilder()
                .setTaskId("t_http_codes")
                .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
                .setArtifactSha256(sha)
                .build()

            val r404 = resolver.resolve(task, testNodeId, blobFetcher = {
                OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$it").toString())
            }) { FetchArtifactResponse.getDefaultInstance() }
            assertThat((r404 as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("ARTIFACT_NOT_FOUND")

            val r403 = resolver.resolve(task, testNodeId, blobFetcher = {
                OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$it").toString())
            }) { FetchArtifactResponse.getDefaultInstance() }
            assertThat((r403 as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("AUTH_REJECTED")

            val r413 = resolver.resolve(task, testNodeId, blobFetcher = {
                OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$it").toString())
            }) { FetchArtifactResponse.getDefaultInstance() }
            assertThat((r413 as ArtifactResolution.TerminalFailure).errorCode).isEqualTo("PAYLOAD_TOO_LARGE")

            val r503 = resolver.resolve(task, testNodeId, blobFetcher = {
                OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/$it").toString())
            }) { FetchArtifactResponse.getDefaultInstance() }
            assertThat(r503).isInstanceOf(ArtifactResolution.TransientError::class.java)
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun okHttpBlobFetcher_coroutineCancellation_abortsCallWithin200Ms(): Unit = kotlinx.coroutines.runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(ByteArray(512 * 1024)))
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
        )
        server.start()

        val client = OkHttpClient()
        try {
            var caughtCancel = false
            val job = launch(Dispatchers.Default) {
                try {
                    OkHttpBlobFetcher.fetch(client, server.url("/v1/blobs/slow").toString())
                } catch (e: CancellationException) {
                    caughtCancel = true
                    throw e
                }
            }
            delay(50)
            val t0 = System.nanoTime()
            job.cancel()
            withTimeout(1000L) { job.join() }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

            assertThat(job.isCancelled).isTrue()
            assertThat(caughtCancel).isTrue()
            assertThat(elapsedMs).isLessThan(200.0)
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
