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
import java.security.MessageDigest

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
 * Resolves decoupled WASM payloads (`wasm_bytecode` empty + `artifact_sha256` present)
 * at the transport boundary via [ArtifactMemoryCache] and mTLS `FetchArtifact` RPC.
 *
 * Traces to: [INV-002], [INV-003], [NFR-PERF-001], [NFR-POW-001], [REQ-OP-003], [REQ-VER-003]
 */
class ArtifactResolver(
    private val cache: ArtifactMemoryCache = ArtifactMemoryCache()
) {
    /**
     * Resolves [task] into an executable [TaskSpec] with populated `wasm_bytecode`.
     * Uses [fetchRpc] only on cache miss, keeping compute engines 100% network-free.
     */
    suspend fun resolve(
        task: TaskSpec,
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

        val response = try {
            fetchRpc(
                FetchArtifactRequest.newBuilder()
                    .setArtifactSha256(expectedSha)
                    .build()
            )
        } catch (e: Throwable) {
            return mapRpcError(e)
        }

        val bytes = response.wasmBytecode
        val maxBytes = ContractConstants.MAX_FRAME_SIZE_BYTES.toInt()
        if (bytes.size() > maxBytes) {
            return ArtifactResolution.TerminalFailure(
                errorCode = "PAYLOAD_TOO_LARGE",
                errorMessage = "Fetched WASM bytecode (${bytes.size()} bytes) exceeds 16 MB frame limit [NFR-PERF-001]"
            )
        }

        val computedSha = computeSha256Hex(bytes.toByteArray())
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
            else -> ArtifactResolution.TransientError(e)
        }
    }

    private fun isValidSha256Hex(value: String): Boolean {
        return value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
