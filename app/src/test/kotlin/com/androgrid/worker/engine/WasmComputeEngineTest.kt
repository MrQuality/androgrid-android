package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Unit tests for in-memory WebAssembly / WASI compute execution and sandbox verification.
 *
 * Traces to: [INV-006], [REQ-VER-003], [NFR-PERF-001]
 */
class WasmComputeEngineTest {

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testBytecodeIntegrity_validSha256Passes() = runTest {
        val engine = WasmComputeEngine()
        val dummyBytecode = "dummy wasm bytecode payload".toByteArray(Charsets.UTF_8)
        val validSha256 = computeSha256(dummyBytecode)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_01")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(dummyBytecode))
            .setArtifactSha256(validSha256)
            .build()

        val result = engine.execute(task)
        assertThat(result.errorCode).isNotEqualTo("PAYLOAD_CORRUPT")
    }

    @Test
    fun testBytecodeIntegrity_corruptedSha256Rejected() = runTest {
        val engine = WasmComputeEngine()
        val dummyBytecode = "corrupted wasm bytecode".toByteArray(Charsets.UTF_8)
        val invalidSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_02")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(dummyBytecode))
            .setArtifactSha256(invalidSha256)
            .build()

        val result = engine.execute(task)
        assertThat(result.exitCode).isNotEqualTo(0)
        assertThat(result.errorCode).isEqualTo("PAYLOAD_CORRUPT")
        assertThat(result.errorMessage).contains("SHA-256 mismatch")
    }

    @Test
    fun testTimeoutWatchdog_terminatesRunawayExecution() = runTest {
        val engine = WasmComputeEngine(
            executionRunner = { _, _ ->
                // Simulate infinite compute loop
                kotlinx.coroutines.delay(5000)
                TaskExecutionResult.success("completed".toByteArray())
            }
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_timeout")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(1) // 1 second timeout
            .setWasmBytecode(ByteString.copyFrom("wasm".toByteArray()))
            .build()

        val result = engine.execute(task)
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.errorMessage).contains("exceeded timeout")
    }

    @Test
    fun testInMemoryExecution_neverWritesDynamicLibrariesToDisk() = runTest {
        // Enforces [REQ-VER-003]: In-memory W^X compliance; no .so files written to disk
        val tempDir = File(System.getProperty("java.io.tmpdir"), "androgrid_wasm_test")
        tempDir.mkdirs()

        val engine = WasmComputeEngine()
        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_mem")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(5)
            .setWasmBytecode(ByteString.copyFrom("in-memory payload".toByteArray()))
            .build()

        engine.execute(task)

        val leakedSoFiles = tempDir.walkTopDown().filter { it.extension == "so" }.toList()
        assertThat(leakedSoFiles).isEmpty()

        tempDir.deleteRecursively()
    }
}
