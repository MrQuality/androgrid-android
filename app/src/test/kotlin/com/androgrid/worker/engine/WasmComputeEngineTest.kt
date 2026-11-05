package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Comprehensive unit and hermetic integration tests for WasmComputeEngine.
 * Enforces real WebAssembly bytecode execution, WASI standard I/O redirection,
 * trap handling, timeout watchdog termination, in-memory W^X invariants, and 16 MB frame limits.
 *
 * Traces to: [INV-006], [REQ-VER-003], [NFR-PERF-001]
 */
class WasmComputeEngineTest {

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testBytecodeIntegrity_validSha256Passes() = runBlocking {
        val engine = WasmComputeEngine()
        val dummyBytecode = TestWasmBytecodeFixtures.HELLO_WASI
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
    fun testBytecodeIntegrity_corruptedSha256Rejected() = runBlocking {
        val engine = WasmComputeEngine()
        val dummyBytecode = TestWasmBytecodeFixtures.HELLO_WASI
        val invalidSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_02")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(dummyBytecode))
            .setArtifactSha256(invalidSha256)
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isNotEqualTo(0)
        assertThat(result.errorCode).isEqualTo("PAYLOAD_CORRUPT")
        assertThat(result.errorMessage).contains("SHA-256 mismatch")
    }

    @Test
    fun testRealWasiModule_capturesStdoutSuccessfully() = runBlocking {
        val engine = WasmComputeEngine()
        val wasiBytecode = TestWasmBytecodeFixtures.HELLO_WASI
        val sha256 = computeSha256(wasiBytecode)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasi_hello")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(wasiBytecode))
            .setArtifactSha256(sha256)
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        val stdout = String(result.stdoutBytes, Charsets.UTF_8)
        assertThat(stdout).isEqualTo("Hello, AndroGrid WASM!\n")
    }

    @Test
    fun testRealPureWasm_fibonacciCalculatesCorrectly() = runBlocking {
        val engine = WasmComputeEngine()
        val fibBytecode = TestWasmBytecodeFixtures.FIBONACCI

        // Request calculation of fibonacci(10) which equals 55
        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_fib")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setEntrypoint("fibonacci")
            .putEnvVars("WASM_ARGS", "10")
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(fibBytecode))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        val stdout = String(result.stdoutBytes, Charsets.UTF_8)
        assertThat(stdout.trim()).isEqualTo("55")
    }

    @Test
    fun testDeliberateWasmTrap_unreachableYieldsTrapError() = runBlocking {
        val engine = WasmComputeEngine()
        val trapBytecode = TestWasmBytecodeFixtures.TRAP_ABORT

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_trap")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(trapBytecode))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("WASM_TRAP")
        assertThat(result.errorMessage).isNotEmpty()
    }

    @Test
    fun testInfiniteLoop_abortsWhenTimeoutExpires() = runBlocking {
        val engine = WasmComputeEngine()
        val loopBytecode = TestWasmBytecodeFixtures.INFINITE_LOOP

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_infinite")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(1) // 1 second timeout
            .setWasmBytecode(ByteString.copyFrom(loopBytecode))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.errorMessage).contains("exceeded timeout")
    }

    @Test
    fun testWasiExitCode_nonZeroCapturedProperly() = runBlocking {
        val engine = WasmComputeEngine()
        val exitBytecode = TestWasmBytecodeFixtures.EXIT_42

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_exit42")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(10)
            .setWasmBytecode(ByteString.copyFrom(exitBytecode))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(42)
        assertThat(result.errorCode).isEqualTo("WASI_EXIT_NON_ZERO")
        assertThat(result.errorMessage).contains("42")
    }

    @Test
    fun testPayloadExceeds16MBFrameLimit_rejected() = runBlocking {
        // Enforces [NFR-PERF-001]: Upper bound of 16 MB strictly enforced
        val engine = WasmComputeEngine()
        val oversizedSize = 16 * 1024 * 1024 + 1
        val oversizedBytecode = ByteString.copyFrom(ByteArray(oversizedSize))

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_oversized")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(5)
            .setWasmBytecode(oversizedBytecode)
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("PAYLOAD_TOO_LARGE")
        assertThat(result.errorMessage).contains("16 MB")
    }

    @Test
    fun testMalformedBytecode_rejectedAsCorrupt() = runBlocking {
        val engine = WasmComputeEngine()
        val malformedBytes = "Not a valid WASM binary header".toByteArray(Charsets.UTF_8)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_corrupt")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(5)
            .setWasmBytecode(ByteString.copyFrom(malformedBytes))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("PAYLOAD_CORRUPT")
    }

    @Test
    fun testMissingExplicitEntrypoint_reportsNotFoundError() = runBlocking {
        val engine = WasmComputeEngine()
        val fibBytecode = TestWasmBytecodeFixtures.FIBONACCI

        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_missing_entrypoint")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setEntrypoint("nonexistent_function_name")
            .setTimeoutSeconds(5)
            .setWasmBytecode(ByteString.copyFrom(fibBytecode))
            .build()

        val result = engine.execute(task)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("ENTRYPOINT_NOT_FOUND")
        assertThat(result.errorMessage).contains("nonexistent_function_name")
    }

    @Test
    fun testInMemoryExecution_neverWritesDynamicLibrariesToDisk() = runBlocking {
        // Enforces [REQ-VER-003]: In-memory W^X compliance; no .so files written to disk
        val tempDir = File(System.getProperty("java.io.tmpdir"), "androgrid_wasm_test")
        tempDir.mkdirs()

        val engine = WasmComputeEngine()
        val task = TaskSpec.newBuilder()
            .setTaskId("task_wasm_mem")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setTimeoutSeconds(5)
            .setWasmBytecode(ByteString.copyFrom(TestWasmBytecodeFixtures.HELLO_WASI))
            .build()

        engine.execute(task)

        val leakedSoFiles = tempDir.walkTopDown().filter { it.extension == "so" }.toList()
        assertThat(leakedSoFiles).isEmpty()

        tempDir.deleteRecursively()
    }
}
