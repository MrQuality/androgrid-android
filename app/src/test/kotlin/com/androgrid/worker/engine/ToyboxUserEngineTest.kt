package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for Unprivileged Toybox / User Shell Execution Engine.
 *
 * Traces to: [INV-006], [NFR-PERF-001]
 */
class ToyboxUserEngineTest {

    private class FakeProcessRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "user output",
        private val stderr: String = "",
        private val delayMs: Long = 0L
    ) : ShellProcessRunner {
        var executedCommand: String? = null
        var executedEnvironment: Map<String, String>? = null

        override suspend fun runProcess(
            command: List<String>,
            environment: Map<String, String>,
            timeoutSeconds: Long
        ): ProcessOutput {
            executedCommand = command.joinToString(" ")
            executedEnvironment = environment
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }
            return ProcessOutput(
                exitCode = exitCode,
                stdoutBytes = stdout.toByteArray(Charsets.UTF_8),
                stderrBytes = stderr.toByteArray(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun testUserCommandExecution_passesCommandAndEnvironment() = runTest {
        val fakeRunner = FakeProcessRunner(exitCode = 0, stdout = "hello androgrid")
        val engine = ToyboxUserEngine(processRunner = fakeRunner)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_toybox_01")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER)
            .setShellCommand("echo hello androgrid")
            .putEnvVars("NODE_ID", "android_node_01")
            .setTimeoutSeconds(5)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdoutString()).isEqualTo("hello androgrid")
        assertThat(fakeRunner.executedCommand).contains("sh -c echo hello androgrid")
        assertThat(fakeRunner.executedEnvironment).containsEntry("NODE_ID", "android_node_01")
    }

    @Test
    fun testUserCommandExecution_capturesNonZeroExitAndStderr() = runTest {
        val fakeRunner = FakeProcessRunner(
            exitCode = 127,
            stdout = "",
            stderr = "toybox: command not found"
        )
        val engine = ToyboxUserEngine(processRunner = fakeRunner)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_toybox_02")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER)
            .setShellCommand("unknown_binary")
            .setTimeoutSeconds(5)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(127)
        assertThat(result.stderrString()).isEqualTo("toybox: command not found")
        assertThat(result.errorCode).isEqualTo("PROCESS_ERROR")
    }

    @Test
    fun testTimeoutWatchdog_terminatesRunawayCommand() = runTest {
        val fakeRunner = FakeProcessRunner(delayMs = 3000L)
        val engine = ToyboxUserEngine(processRunner = fakeRunner)

        val task = TaskSpec.newBuilder()
            .setTaskId("task_toybox_timeout")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER)
            .setShellCommand("sleep 60")
            .setTimeoutSeconds(1)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.errorMessage).contains("exceeded timeout")
    }
}
