package com.androgrid.worker.engine

import com.androgrid.v1.SubmitCellularResultRequest
import com.androgrid.v1.TaskExecutionReport
import com.androgrid.v1.TaskState
import com.google.protobuf.ByteString

/**
 * Standardized execution outcome from compute engines.
 * Bridges local execution artifacts with wire-format submission requests.
 *
 * Traces to: [REQ-OP-002], [REQ-OP-004], [NFR-PERF-001]
 */
data class TaskExecutionResult(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderrBytes: ByteArray,
    val errorCode: String = "",
    val errorMessage: String = "",
    val executionDurationMs: Long = 0L
) {
    /**
     * True if the task execution succeeded with zero exit code and no error code.
     * Traces to: [REQ-OP-004]
     */
    val isSuccess: Boolean
        get() = exitCode == 0 && errorCode.isEmpty()

    /**
     * Decodes stdoutBytes as UTF-8 string for logging and assertion.
     */
    fun stdoutString(): String = stdoutBytes.toString(Charsets.UTF_8).trim()

    /**
     * Decodes stderrBytes as UTF-8 string for diagnostic reporting.
     */
    fun stderrString(): String = stderrBytes.toString(Charsets.UTF_8).trim()

    /**
     * Converts domain result to canonical SubmitCellularResultRequest for metered WAN delivery.
     *
     * Traces to: [REQ-OP-004]
     */
    fun toSubmitCellularResultRequest(
        nodeId: String,
        taskId: String,
        runAttempt: Int
    ): SubmitCellularResultRequest {
        return SubmitCellularResultRequest.newBuilder()
            .setNodeId(nodeId)
            .setTaskId(taskId)
            .setRunAttempt(runAttempt)
            .setExitCode(exitCode)
            .setStdoutBytes(ByteString.copyFrom(stdoutBytes))
            .setStderrBytes(ByteString.copyFrom(stderrBytes))
            .setErrorCode(errorCode)
            .setErrorMessage(errorMessage)
            .setExecutionDurationMs(executionDurationMs)
            .build()
    }

    /**
     * Converts domain result to TaskExecutionReport for declarative Wi-Fi synchronization.
     *
     * Traces to: [REQ-OP-002]
     */
    fun toTaskExecutionReport(
        taskId: String,
        runAttempt: Int,
        state: TaskState
    ): TaskExecutionReport {
        return TaskExecutionReport.newBuilder()
            .setTaskId(taskId)
            .setRunAttempt(runAttempt)
            .setState(state)
            .setExitCode(exitCode)
            .setStdoutBytes(ByteString.copyFrom(stdoutBytes))
            .setStderrBytes(ByteString.copyFrom(stderrBytes))
            .setErrorCode(errorCode)
            .setExecutionTimeMs(executionDurationMs)
            .build()
    }

    companion object {
        /**
         * Factory constructor for successful execution.
         */
        fun success(
            stdoutBytes: ByteArray = ByteArray(0),
            durationMs: Long = 0L
        ): TaskExecutionResult = TaskExecutionResult(
            exitCode = 0,
            stdoutBytes = stdoutBytes,
            stderrBytes = ByteArray(0),
            errorCode = "",
            errorMessage = "",
            executionDurationMs = durationMs
        )

        /**
         * Factory constructor for failed execution or watchdog timeout.
         */
        fun failure(
            exitCode: Int = -1,
            errorCode: String,
            errorMessage: String,
            stderrBytes: ByteArray = ByteArray(0),
            durationMs: Long = 0L
        ): TaskExecutionResult = TaskExecutionResult(
            exitCode = exitCode,
            stdoutBytes = ByteArray(0),
            stderrBytes = if (stderrBytes.isNotEmpty()) stderrBytes else errorMessage.toByteArray(Charsets.UTF_8),
            errorCode = errorCode,
            errorMessage = errorMessage,
            executionDurationMs = durationMs
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TaskExecutionResult

        if (exitCode != other.exitCode) return false
        if (!stdoutBytes.contentEquals(other.stdoutBytes)) return false
        if (!stderrBytes.contentEquals(other.stderrBytes)) return false
        if (errorCode != other.errorCode) return false
        if (errorMessage != other.errorMessage) return false
        if (executionDurationMs != other.executionDurationMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = exitCode
        result = 31 * result + stdoutBytes.contentHashCode()
        result = 31 * result + stderrBytes.contentHashCode()
        result = 31 * result + errorCode.hashCode()
        result = 31 * result + errorMessage.hashCode()
        result = 31 * result + executionDurationMs.hashCode()
        return result
    }
}
