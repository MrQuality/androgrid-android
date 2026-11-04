package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.capability.RootChecker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for Root Shell Execution Engine and unrooted sandbox boundary.
 *
 * Traces to: [INV-006]
 */
class RootShellEngineTest {

    private class FakeRootChecker(private val rooted: Boolean) : RootChecker {
        override fun isDeviceRooted(): Boolean = rooted
    }

    private class FakeProcessRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "root output",
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
    fun testUnrootedNode_strictlyRejectsRootShellExecution() = runTest {
        // Enforces [INV-006]: Unrooted devices must never execute root shell tasks
        val fakeRunner = FakeProcessRunner()
        val engine = RootShellEngine(
            rootChecker = FakeRootChecker(rooted = false),
            processRunner = fakeRunner
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_root_01")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
            .setShellCommand("id")
            .setTimeoutSeconds(5)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("PERMISSION_DENIED")
        assertThat(result.errorMessage).contains("Unrooted device rejected root shell execution")
        assertThat(fakeRunner.executedCommand).isNull()
    }

    @Test
    fun testRootedNode_executesCommandViaSu() = runTest {
        val fakeRunner = FakeProcessRunner(exitCode = 0, stdout = "uid=0(root) gid=0(root)")
        val engine = RootShellEngine(
            rootChecker = FakeRootChecker(rooted = true),
            processRunner = fakeRunner
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_root_02")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
            .setShellCommand("id")
            .putEnvVars("CLUSTER_ENV", "production")
            .setTimeoutSeconds(10)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.errorCode).isEmpty()
        assertThat(result.stdoutString()).isEqualTo("uid=0(root) gid=0(root)")
        assertThat(fakeRunner.executedCommand).contains("su -c id")
        assertThat(fakeRunner.executedEnvironment).containsKey("CLUSTER_ENV")
    }

    @Test
    fun testRootedNode_timeoutTriggersWatchdogTermination() = runTest {
        val fakeRunner = FakeProcessRunner(delayMs = 3000L)
        val engine = RootShellEngine(
            rootChecker = FakeRootChecker(rooted = true),
            processRunner = fakeRunner
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_root_timeout")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
            .setShellCommand("sleep 100")
            .setTimeoutSeconds(1)
            .build()

        val result = engine.execute(task)

        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.errorMessage).contains("exceeded timeout")
    }
}
