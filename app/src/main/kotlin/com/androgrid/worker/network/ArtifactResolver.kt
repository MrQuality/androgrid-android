package com.androgrid.worker.network

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.FetchArtifactRequest
import com.androgrid.v1.FetchArtifactResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.contract.ContractConstants
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bounded, byte-weighted in-memory LRU cache for verified WebAssembly modules.
 * Stores bytecode strictly in RAM (zero flash writes) and clears on memory pressure
 * to honor [REQ-VER-003] and [NFR-PERF-001].
 *
 * Traces to: [REQ-VER-003], [INV-006], [NFR-PERF-001], [NFR-POW-001]
 */
class ArtifactMemoryCache(
    private val maxSizeBytes: Int = DEFAULT_MAX_CACHE_BYTES
) {
    companion object {
        const val DEFAULT_MAX_CACHE_BYTES: Int = 32 * 1024 * 1024 // 32 MB
    }

    private val map = LinkedHashMap<String, ByteString>(16, 0.75f, true)
    private var totalBytes: Int = 0

    /** Retrieves verified bytecode by lowercase 64-char SHA-256 hex digest, or null on miss. */
    @Synchronized
    fun get(sha256: String): ByteString? = map[sha256.lowercase()]

    /** Caches verified bytecode keyed by lowercase 64-char SHA-256 hex digest. */
    @Synchronized
    fun put(sha256: String, bytes: ByteString) {
        val entrySize = bytes.size()
        if (entrySize > maxSizeBytes) {
            return
        }
        val key = sha256.lowercase()
        val previous = map.put(key, bytes)
        if (previous != null) {
            totalBytes -= previous.size()
        }
        totalBytes += entrySize

        val iter = map.entries.iterator()
        while (totalBytes > maxSizeBytes && iter.hasNext()) {
            val eldest = iter.next()
            totalBytes -= eldest.value.size()
            iter.remove()
        }
    }

    /** Evicts all cached modules (e.g., on ComponentCallbacks2.onTrimMemory). */
    @Synchronized
    fun clear() {
        map.clear()
        totalBytes = 0
    }

    /** Current cached byte count (primarily for observability and tests). */
    @Synchronized
    fun currentBytes(): Int = totalBytes
}

/**
 * Outcome of transport-layer artifact resolution prior to offline compute execution.
 *
 * Traces to: [INV-002], [INV-003], [NFR-PERF-001], [NFR-POW-001], [REQ-OP-003]
 */
sealed class ArtifactResolution {
    data class Ready(val task: TaskSpec) : ArtifactResolution()
    data class TerminalFailure(val errorCode: String, val errorMessage: String) : ArtifactResolution()
    data class TransientError(val cause: Throwable) : ArtifactResolution()
}

/**
 * Exception thrown by [OkHttpBlobFetcher] on non-2xx HTTP responses from `/v1/blobs/{sha256}`.
 */
class BlobHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

/**
 * Exception thrown by [OkHttpBlobFetcher] when streamed blob size or framing violates bounds.
 */
class BlobPayloadException(
    val errorCode: String,
    message: String
) : IOException(message)

/**
 * Shared mTLS HTTP/2 `/v1/blobs/{sha256}` downloader enforcing `[NFR-PERF-001]` bounds,
 * stream truncation detection, and cooperative `Call.cancel()`.
 *
 * Traces to: [INV-002], [NFR-PERF-001], [NFR-POW-001], [REQ-OP-003], [REQ-VER-003]
 */
object OkHttpBlobFetcher {

    /**
     * Builds an [OkHttpClient] pinned to [mtlsContext].
     *
     * @param mtlsContext configured client/CA SSL context.
     * @param allowLocalhostLoopback permits SAN `"localhost"` when connecting via `10.0.2.2` / `127.0.0.1`.
     * @param ephemeralPool disables keep-alive pooling for `[NFR-POW-001]` cellular teardown.
     */
    fun buildMtlsClient(
        mtlsContext: MtlsContext,
        allowLocalhostLoopback: Boolean = false,
        ephemeralPool: Boolean = false
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(mtlsContext.socketFactory, mtlsContext.trustManager)
        if (ephemeralPool) {
            builder.connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        }
        if (allowLocalhostLoopback) {
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            builder.hostnameVerifier { hostname, session ->
                hostname == "10.0.2.2" || hostname == "127.0.0.1" || hostname == "localhost" ||
                    defaultVerifier.verify(hostname, session)
            }
        }
        return builder.build()
    }

    /**
     * Downloads a blob from [url] using [client], respecting coroutine cancellation and `[NFR-PERF-001]` bounds.
     *
     * @throws BlobHttpException on non-2xx HTTP responses.
     * @throws BlobPayloadException on empty, oversized, or truncated payloads.
     */
    suspend fun fetch(
        client: OkHttpClient,
        url: String,
        maxBytes: Int = ContractConstants.MAX_FRAME_SIZE_BYTES.toInt()
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = BlobHttpException(
                            response.code,
                            "HTTP ${response.code} fetching blob"
                        )
                        if (cont.isActive) cont.resumeWithException(err)
                        return@suspendCancellableCoroutine
                    }

                    val body = response.body
                        ?: throw BlobPayloadException("PAYLOAD_CORRUPT", "Missing HTTP response body")
                    val headerSize = response.header("X-Artifact-Size")?.toLongOrNull()
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val expectedSize = headerSize ?: contentLength

                    val resultBytes = if (expectedSize != null) {
                        if (expectedSize == 0L) {
                            throw BlobPayloadException("PAYLOAD_CORRUPT", "Blob payload must not be empty")
                        }
                        if (expectedSize > maxBytes.toLong()) {
                            throw BlobPayloadException(
                                "PAYLOAD_TOO_LARGE",
                                "Blob size ($expectedSize bytes) exceeds $maxBytes byte limit [NFR-PERF-001]"
                            )
                        }
                        val target = expectedSize.toInt()
                        val buffer = ByteArray(target)
                        var totalRead = 0
                        body.byteStream().use { input ->
                            while (totalRead < target) {
                                val read = input.read(buffer, totalRead, target - totalRead)
                                if (read < 0) break
                                totalRead += read
                            }
                            if (totalRead != target || input.read() != -1) {
                                throw BlobPayloadException(
                                    "PAYLOAD_CORRUPT",
                                    "Truncated or mismatched stream: expected $target bytes, read $totalRead"
                                )
                            }
                        }
                        buffer
                    } else {
                        val out = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var totalRead = 0
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(chunk)
                                if (read < 0) break
                                totalRead += read
                                if (totalRead > maxBytes) {
                                    throw BlobPayloadException(
                                        "PAYLOAD_TOO_LARGE",
                                        "Chunked blob exceeded $maxBytes byte limit [NFR-PERF-001]"
                                    )
                                }
                                out.write(chunk, 0, read)
                            }
                        }
                        if (totalRead == 0) {
                            throw BlobPayloadException("PAYLOAD_CORRUPT", "Blob payload must not be empty")
                        }
                        out.toByteArray()
                    }

                    if (cont.isActive) {
                        cont.resume(resultBytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (cont.isActive) {
                    cont.resumeWithException(t)
                }
            }
        }
    }
}

/**
 * Resolves decoupled WASM payloads (`wasm_bytecode` empty + `artifact_sha256` present)
 * at the transport boundary via [ArtifactMemoryCache], `/v1/blobs/{sha256}` HTTPS fetchers,
 * or legacy mTLS `FetchArtifact` RPC.
 *
 * Traces to: [INV-002], [INV-003], [NFR-PERF-001], [NFR-POW-001], [REQ-OP-003], [REQ-VER-003]
 */
class ArtifactResolver(
    private val cache: ArtifactMemoryCache = ArtifactMemoryCache()
) {
    /**
     * Resolves [task] into an executable [TaskSpec] with populated `wasm_bytecode`.
     * Uses [blobFetcher] when available, falling back to [fetchRpc] only when [blobFetcher] is `null`.
     */
    suspend fun resolve(
        task: TaskSpec,
        nodeId: String,
        blobFetcher: (suspend (String) -> ByteArray)? = null,
        fetchRpc: suspend (FetchArtifactRequest) -> FetchArtifactResponse
    ): ArtifactResolution {
        if (task.engine != ExecutionEngine.EXECUTION_ENGINE_WASM_WASI || !task.wasmBytecode.isEmpty) {
            return ArtifactResolution.Ready(task)
        }

        val expectedSha = task.artifactSha256.trim().lowercase()
        if (!isValidSha256Hex(expectedSha)) {
            return ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = "Missing or invalid artifact_sha256 for decoupled WASM task"
            )
        }

        cache.get(expectedSha)?.let { cached ->
            return ArtifactResolution.Ready(
                task.toBuilder().setWasmBytecode(cached).build()
            )
        }

        val bytes = if (blobFetcher != null) {
            try {
                ByteString.copyFrom(blobFetcher(expectedSha))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlobPayloadException) {
                return ArtifactResolution.TerminalFailure(
                    errorCode = e.errorCode,
                    errorMessage = e.message ?: e.errorCode
                )
            } catch (e: BlobHttpException) {
                return mapHttpError(e)
            } catch (e: Throwable) {
                return ArtifactResolution.TransientError(e)
            }
        } else {
            val response = try {
                fetchRpc(
                    FetchArtifactRequest.newBuilder()
                        .setArtifactSha256(expectedSha)
                        .setNodeId(nodeId)
                        .build()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return mapRpcError(e)
            }
            response.wasmBytecode
        }

        val maxBytes = ContractConstants.MAX_FRAME_SIZE_BYTES.toInt()
        if (bytes.isEmpty) {
            return ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = "Fetched WASM bytecode must not be empty"
            )
        }
        if (bytes.size() > maxBytes) {
            return ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_TOO_LARGE",
                errorMessage = "Fetched WASM bytecode (${bytes.size()} bytes) exceeds 16 MB frame limit [NFR-PERF-001]"
            )
        }

        val computedSha = computeSha256Hex(bytes)
        if (computedSha != expectedSha) {
            return ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = "Fetched bytecode SHA-256 mismatch: expected $expectedSha, got $computedSha"
            )
        }

        cache.put(expectedSha, bytes)
        return ArtifactResolution.Ready(
            task.toBuilder().setWasmBytecode(bytes).build()
        )
    }

    private fun mapHttpError(e: BlobHttpException): ArtifactResolution {
        val detail = e.message ?: "HTTP ${e.statusCode}"
        return when (e.statusCode) {
            404 -> ArtifactResolution.TerminalFailure(
                errorCode = "ARTIFACT_NOT_FOUND",
                errorMessage = detail
            )
            401, 403 -> ArtifactResolution.TerminalFailure(
                errorCode = "AUTH_REJECTED",
                errorMessage = detail
            )
            413 -> ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_TOO_LARGE",
                errorMessage = detail
            )
            400, 416, 422 -> ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = detail
            )
            else -> ArtifactResolution.TransientError(e)
        }
    }

    private fun mapRpcError(e: Throwable): ArtifactResolution {
        val code = when (e) {
            is StatusException -> e.status.code
            is StatusRuntimeException -> e.status.code
            else -> return ArtifactResolution.TransientError(e)
        }
        val detail = e.message ?: code.name
        return when (code) {
            Status.Code.NOT_FOUND -> ArtifactResolution.TerminalFailure(
                errorCode = "ARTIFACT_NOT_FOUND",
                errorMessage = detail
            )
            Status.Code.INVALID_ARGUMENT, Status.Code.FAILED_PRECONDITION -> ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = detail
            )
            Status.Code.RESOURCE_EXHAUSTED -> ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_TOO_LARGE",
                errorMessage = detail
            )
            Status.Code.UNIMPLEMENTED -> ArtifactResolution.TerminalFailure(
                errorCode = "RPC_UNIMPLEMENTED",
                errorMessage = detail
            )
            Status.Code.PERMISSION_DENIED, Status.Code.UNAUTHENTICATED -> ArtifactResolution.TerminalFailure(
                errorCode = "AUTH_REJECTED",
                errorMessage = detail
            )
            else -> ArtifactResolution.TransientError(e)
        }
    }

    private fun isValidSha256Hex(value: String): Boolean {
        return value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun computeSha256Hex(bytes: ByteString): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes.asReadOnlyByteBuffer())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
