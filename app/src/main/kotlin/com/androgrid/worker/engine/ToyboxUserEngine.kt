package com.androgrid.worker.engine

import com.androgrid.v1.TaskSpec

/**
 * Executes unprivileged user shell commands within Android application sandbox.
 * Enforces timeout bounds and stream capturing.
 *
 * Traces to: [INV-006], [NFR-PERF-001]
 */
class ToyboxUserEngine(
    private val processRunner: ShellProcessRunner = StandardShellProcessRunner()
) : ComputeEngine {

    override suspend fun execute(task: TaskSpec): TaskExecutionResult {
        val command = task.shellCommand.trim()
        if (command.isEmpty()) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "INVALID_COMMAND",
                errorMessage = "Shell command was empty in TaskSpec"
            )
        }

        val timeoutSeconds = if (task.timeoutSeconds > 0) task.timeoutSeconds.toLong() else 180L
        val processCommand = listOf("sh", "-c", command)

        val output = processRunner.runProcess(
            command = processCommand,
            environment = task.envVarsMap,
            timeoutSeconds = timeoutSeconds
        )

        return if (output.exitCode == 0) {
            TaskExecutionResult.success(stdoutBytes = output.stdoutBytes)
        } else {
            val isTimeout = output.stderrBytes.toString(Charsets.UTF_8).contains("exceeded timeout")
            val errorCode = if (isTimeout) "TIMEOUT" else "PROCESS_ERROR"
            val errorMessage = output.stderrBytes.toString(Charsets.UTF_8).ifEmpty {
                "Process exited with non-zero code ${output.exitCode}"
            }
            TaskExecutionResult.failure(
                exitCode = output.exitCode,
                errorCode = errorCode,
                errorMessage = errorMessage,
                stderrBytes = output.stderrBytes
            )
        }
    }
}
