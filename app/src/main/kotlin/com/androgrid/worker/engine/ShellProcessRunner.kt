package com.androgrid.worker.engine

import com.androgrid.worker.contract.ContractConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Raw output emitted by child operating system process.
 *
 * Traces to: [NFR-PERF-001]
 */
data class ProcessOutput(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderrBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessOutput

        if (exitCode != other.exitCode) return false
        if (!stdoutBytes.contentEquals(other.stdoutBytes)) return false
        if (!stderrBytes.contentEquals(other.stderrBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = exitCode
        result = 31 * result + stdoutBytes.contentHashCode()
        result = 31 * result + stderrBytes.contentHashCode()
        return result
    }
}

/**
 * Interface abstracting system process execution for testability and isolation.
 *
 * Traces to: [INV-006]
 */
interface ShellProcessRunner {
    /**
     * Executes OS command with environment variables and timeout limit.
     *
     * @param command list of tokens representing command line arguments.
     * @param environment custom environment variables.
     * @param timeoutSeconds execution time limit in seconds.
     * @return captured ProcessOutput.
     */
    suspend fun runProcess(
        command: List<String>,
        environment: Map<String, String>,
        timeoutSeconds: Long
    ): ProcessOutput
}

/**
 * Production process runner utilizing ProcessBuilder with stream draining and watchdog termination.
 * Enforces buffer boundaries ([NFR-PERF-001]).
 *
 * Traces to: [INV-006], [NFR-PERF-001]
 */
class StandardShellProcessRunner(
    private val maxStreamSizeBytes: Int = ContractConstants.MAX_FRAME_SIZE_BYTES.toInt()
) : ShellProcessRunner {

    override suspend fun runProcess(
        command: List<String>,
        environment: Map<String, String>,
        timeoutSeconds: Long
    ): ProcessOutput = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder(command)
        val env = processBuilder.environment()
        environment.forEach { (k, v) -> env[k] = v }

        val process = processBuilder.start()

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()

        val stdoutDrainer = Thread { drainStream(process.inputStream, stdoutStream) }
        val stderrDrainer = Thread { drainStream(process.errorStream, stderrStream) }

        stdoutDrainer.start()
        stderrDrainer.start()

        val effectiveTimeout = if (timeoutSeconds > 0) timeoutSeconds else 180L
        val finishedInTime = process.waitFor(effectiveTimeout, TimeUnit.SECONDS)

        if (!finishedInTime) {
            process.destroyForcibly()
            stdoutDrainer.join(200)
            stderrDrainer.join(200)
            return@withContext ProcessOutput(
                exitCode = -1,
                stdoutBytes = stdoutStream.toByteArray(),
                stderrBytes = "Process exceeded timeout of ${effectiveTimeout}s and was forcibly terminated"
                    .toByteArray(Charsets.UTF_8)
            )
        }

        stdoutDrainer.join(500)
        stderrDrainer.join(500)

        ProcessOutput(
            exitCode = process.exitValue(),
            stdoutBytes = stdoutStream.toByteArray(),
            stderrBytes = stderrStream.toByteArray()
        )
    }

    private fun drainStream(inputStream: InputStream, outputStream: ByteArrayOutputStream) {
        try {
            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalRead = 0
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (totalRead + bytesRead <= maxStreamSizeBytes) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                } else {
                    val remaining = maxStreamSizeBytes - totalRead
                    if (remaining > 0) {
                        outputStream.write(buffer, 0, remaining)
                        totalRead += remaining
                    }
                    break
                }
            }
        } catch (_: Exception) {
            // Stream closed or process terminated
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {}
        }
    }
}
