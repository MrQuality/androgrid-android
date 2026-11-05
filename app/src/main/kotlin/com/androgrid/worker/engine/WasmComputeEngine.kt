package com.androgrid.worker.engine

import com.androgrid.v1.TaskSpec
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Store
import com.dylibso.chicory.runtime.WasmRuntimeException
import com.dylibso.chicory.wasi.WasiExitException
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * In-memory WebAssembly / WASI compute engine for isolated edge workloads.
 * Interprets compiled WebAssembly bytecode entirely in memory via Chicory without emitting,
 * writing, or executing dynamic shared libraries (.so) on writable storage ([REQ-VER-003]).
 *
 * Enforces SHA-256 integrity verification, 16 MB frame payload limits ([NFR-PERF-001]),
 * WASI Preview 1 standard stream redirection, runtime trap isolation, and timeout watchdogs.
 *
 * Traces to: [INV-006], [REQ-VER-003], [NFR-PERF-001]
 */
class WasmComputeEngine(
    private val executionRunner: (suspend (ByteString, Map<String, String>) -> TaskExecutionResult)? = null
) : ComputeEngine {

    companion object {
        /**
         * Upper bound of 16 MB strictly enforced on gRPC message buffers and bytecode payloads.
         * Traces to: [NFR-PERF-001]
         */
        const val MAX_FRAME_SIZE_BYTES: Int = 16 * 1024 * 1024
    }

    /**
     * Executes the WebAssembly task specification within the in-memory WASI sandbox.
     *
     * @param task TaskSpec containing bytecode, entrypoint, environment variables, and timeout.
     * @return TaskExecutionResult containing exit code, stdout, stderr, or failure error codes.
     */
    override suspend fun execute(task: TaskSpec): TaskExecutionResult {
        // Step 1: Enforce [NFR-PERF-001] 16 MB payload upper bound
        if (task.wasmBytecode.size() > MAX_FRAME_SIZE_BYTES) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "PAYLOAD_TOO_LARGE",
                errorMessage = "WASM bytecode size (${task.wasmBytecode.size()} bytes) exceeds maximum 16 MB frame limit [NFR-PERF-001]"
            )
        }

        // Step 2: Verify SHA-256 integrity if specified
        if (task.artifactSha256.isNotEmpty()) {
            val computedHash = computeSha256(task.wasmBytecode.toByteArray())
            if (!computedHash.equals(task.artifactSha256, ignoreCase = true)) {
                return TaskExecutionResult.failure(
                    exitCode = -1,
                    errorCode = "PAYLOAD_CORRUPT",
                    errorMessage = "Bytecode SHA-256 mismatch: expected ${task.artifactSha256}, got $computedHash"
                )
            }
        }

        // Step 3: Enforce execution timeout watchdog
        val timeoutSeconds = if (task.timeoutSeconds > 0) task.timeoutSeconds.toLong() else 180L
        val timeoutMillis = timeoutSeconds * 1000L

        val result = withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.Default) {
                try {
                    if (executionRunner != null) {
                        executionRunner.invoke(task.wasmBytecode, task.envVarsMap)
                    } else {
                        executeWasmInMemory(task.wasmBytecode, task.envVarsMap, task.entrypoint)
                    }
                } catch (t: Throwable) {
                    TaskExecutionResult.failure(
                        exitCode = -1,
                        errorCode = "WASM_TRAP",
                        errorMessage = "WASM execution trap or unhandled exception: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }

        return result ?: TaskExecutionResult.failure(
            exitCode = -1,
            errorCode = "TIMEOUT",
            errorMessage = "Execution exceeded timeout of ${timeoutSeconds}s"
        )
    }

    /**
     * Executes WASM bytecode purely in-memory using Chicory WASI Preview 1.
     * Enforces [REQ-VER-003]: Does not emit or execute .so files on writable disk storage.
     *
     * @param bytecode raw WebAssembly binary bytecode payload.
     * @param envVars environment variables to inject into WASI runtime environment.
     * @param entrypoint exported function name to invoke (defaults to _start or main).
     * @return TaskExecutionResult with captured stdout, stderr, and exit status.
     */
    private fun executeWasmInMemory(
        bytecode: ByteString,
        envVars: Map<String, String>,
        entrypoint: String
    ): TaskExecutionResult {
        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()

        val wasiOptionsBuilder = WasiOptions.builder()
            .withStdout(stdoutStream)
            .withStderr(stderrStream)

        for ((key, value) in envVars) {
            wasiOptionsBuilder.withEnvironment(key, value)
        }
        val wasiOptions = wasiOptionsBuilder.build()

        val wasi = WasiPreview1.builder()
            .withOptions(wasiOptions)
            .build()

        val store = Store().addFunction(*wasi.toHostFunctions())

        val rawBytes = bytecode.toByteArray()

        // Parse WASM module in-memory
        val module: WasmModule = try {
            Parser.parse(rawBytes)
        } catch (t: Throwable) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "PAYLOAD_CORRUPT",
                errorMessage = "Failed to parse WASM module: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        // Instantiate WASM module instance with WASI host functions
        val instance = try {
            Instance.builder(module)
                .withImportValues(store.toImportValues())
                .build()
        } catch (t: Throwable) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "WASM_TRAP",
                errorMessage = "Failed to instantiate WASM module: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        // Resolve entrypoint function
        val requestedEntrypoint = entrypoint.trim()
        val targetName = if (requestedEntrypoint.isNotEmpty()) requestedEntrypoint else "_start"

        val exportFunc: ExportFunction? = try {
            instance.export(targetName)
        } catch (e: Exception) {
            null
        }

        val resolvedFunc: ExportFunction? = if (exportFunc == null && requestedEntrypoint.isEmpty()) {
            try {
                instance.export("main")
            } catch (e: Exception) {
                null
            }
        } else {
            exportFunc
        }

        val func = resolvedFunc ?: run {
            val missingName = if (requestedEntrypoint.isNotEmpty()) requestedEntrypoint else "_start"
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "ENTRYPOINT_NOT_FOUND",
                errorMessage = "Exported entrypoint function '$missingName' was not found in WASM module"
            )
        }

        // Parse optional numeric parameters from environment variable (e.g. WASM_ARGS="10,20")
        val args = envVars["WASM_ARGS"]
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toLongArray()
            ?: longArrayOf()

        return try {
            val results = if (args.isNotEmpty()) {
                func.apply(*args)
            } else {
                func.apply()
            }

            val stdoutBytes = stdoutStream.toByteArray()
            val stderrBytes = stderrStream.toByteArray()

            // If stdout stream was empty but function returned values, format values as output
            val finalStdout = if (stdoutBytes.isEmpty() && results != null && results.isNotEmpty()) {
                results.joinToString(" ").toByteArray(Charsets.UTF_8)
            } else {
                stdoutBytes
            }

            TaskExecutionResult(
                exitCode = 0,
                stdoutBytes = finalStdout,
                stderrBytes = stderrBytes
            )
        } catch (e: WasiExitException) {
            val stdoutBytes = stdoutStream.toByteArray()
            val stderrBytes = stderrStream.toByteArray()
            val code = e.exitCode()
            if (code == 0) {
                TaskExecutionResult(
                    exitCode = 0,
                    stdoutBytes = stdoutBytes,
                    stderrBytes = stderrBytes
                )
            } else {
                val errorMsg = "WASI module exited with non-zero status: $code"
                TaskExecutionResult(
                    exitCode = code,
                    stdoutBytes = stdoutBytes,
                    stderrBytes = if (stderrBytes.isNotEmpty()) stderrBytes else errorMsg.toByteArray(Charsets.UTF_8),
                    errorCode = "WASI_EXIT_NON_ZERO",
                    errorMessage = errorMsg
                )
            }
        } catch (e: WasmRuntimeException) {
            val stdoutBytes = stdoutStream.toByteArray()
            val stderrBytes = stderrStream.toByteArray()
            val errorMsg = "WASM runtime trap: ${e.message ?: e.javaClass.simpleName}"
            TaskExecutionResult(
                exitCode = -1,
                stdoutBytes = stdoutBytes,
                stderrBytes = if (stderrBytes.isNotEmpty()) stderrBytes else errorMsg.toByteArray(Charsets.UTF_8),
                errorCode = "WASM_TRAP",
                errorMessage = errorMsg
            )
        } catch (t: Throwable) {
            val stdoutBytes = stdoutStream.toByteArray()
            val stderrBytes = stderrStream.toByteArray()
            val errorMsg = "WASM execution trap or unhandled exception: ${t.message ?: t.javaClass.simpleName}"
            TaskExecutionResult(
                exitCode = -1,
                stdoutBytes = stdoutBytes,
                stderrBytes = if (stderrBytes.isNotEmpty()) stderrBytes else errorMsg.toByteArray(Charsets.UTF_8),
                errorCode = "WASM_TRAP",
                errorMessage = errorMsg
            )
        }
    }

    /**
     * Computes the hexadecimal SHA-256 digest of the given byte array.
     *
     * @param bytes byte array to hash.
     * @return 64-character lowercase hexadecimal hash string.
     */
    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
