package com.androgrid.worker.engine

import com.androgrid.v1.TaskSpec
import com.androgrid.worker.capability.RootChecker
import com.androgrid.worker.capability.StandardRootChecker

/**
 * Executes privileged root shell commands strictly on rooted devices.
 * Enforces [INV-006]: Unrooted devices MUST NOT execute root shell tasks.
 *
 * Traces to: [INV-006]
 */
class RootShellEngine(
    private val rootChecker: RootChecker = StandardRootChecker(),
    private val processRunner: ShellProcessRunner = StandardShellProcessRunner()
) : ComputeEngine {

    override suspend fun execute(task: TaskSpec): TaskExecutionResult {
        // Enforces [INV-006]: Unrooted devices strictly forbidden from running root shell
        if (!rootChecker.isDeviceRooted()) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "PERMISSION_DENIED",
                errorMessage = "Unrooted device rejected root shell execution [INV-006]"
            )
        }

        val command = task.shellCommand.trim()
        if (command.isEmpty()) {
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "INVALID_COMMAND",
                errorMessage = "Shell command was empty in TaskSpec"
            )
        }

        val timeoutSeconds = if (task.timeoutSeconds > 0) task.timeoutSeconds.toLong() else 180L
        val processCommand = listOf("su", "-c", command)

        val output = processRunner.runProcess(
            command = processCommand,
            environment = task.envVarsMap,
            timeoutSeconds = timeoutSeconds
        )

        return if (output.exitCode == 0) {
            TaskExecutionResult.success(stdoutBytes = output.stdoutBytes)
        } else {
            val isTimeout = output.stderrBytes.toString(Charsets.UTF_8).contains("exceeded timeout")
            val errorCode = if (isTimeout) "TIMEOUT" else "ROOT_PROCESS_ERROR"
            val errorMessage = output.stderrBytes.toString(Charsets.UTF_8).ifEmpty {
                "Root process exited with code ${output.exitCode}"
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
