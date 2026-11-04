package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec
import com.androgrid.v1.TaskState
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for Central Task Execution Dispatcher.
 *
 * Traces to: [INV-006], [REQ-OP-002], [REQ-OP-004]
 */
class TaskExecutionDispatcherTest {

    private class RecordingEngine(private val name: String) : ComputeEngine {
        var callCount: Int = 0
        var lastTask: TaskSpec? = null

        override suspend fun execute(task: TaskSpec): TaskExecutionResult {
            callCount++
            lastTask = task
            return TaskExecutionResult(
                exitCode = 0,
                stdoutBytes = "result from $name".toByteArray(),
                stderrBytes = ByteArray(0),
                errorCode = "",
                errorMessage = "",
                executionDurationMs = 15L
            )
        }
    }

    @Test
    fun testDispatcher_routesWasmEngine() = runTest {
        val wasm = RecordingEngine("wasm")
        val toybox = RecordingEngine("toybox")
        val root = RecordingEngine("root")

        val dispatcher = TaskExecutionDispatcher(
            wasmEngine = wasm,
            toyboxEngine = toybox,
            rootShellEngine = root
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_dispatch_wasm")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .build()

        val result = dispatcher.dispatch(task)

        assertThat(wasm.callCount).isEqualTo(1)
        assertThat(toybox.callCount).isEqualTo(0)
        assertThat(root.callCount).isEqualTo(0)
        assertThat(result.stdoutString()).isEqualTo("result from wasm")
    }

    @Test
    fun testDispatcher_routesToyboxEngine() = runTest {
        val wasm = RecordingEngine("wasm")
        val toybox = RecordingEngine("toybox")
        val root = RecordingEngine("root")

        val dispatcher = TaskExecutionDispatcher(
            wasmEngine = wasm,
            toyboxEngine = toybox,
            rootShellEngine = root
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_dispatch_toybox")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER)
            .build()

        val result = dispatcher.dispatch(task)

        assertThat(wasm.callCount).isEqualTo(0)
        assertThat(toybox.callCount).isEqualTo(1)
        assertThat(root.callCount).isEqualTo(0)
        assertThat(result.stdoutString()).isEqualTo("result from toybox")
    }

    @Test
    fun testDispatcher_routesRootEngine() = runTest {
        val wasm = RecordingEngine("wasm")
        val toybox = RecordingEngine("toybox")
        val root = RecordingEngine("root")

        val dispatcher = TaskExecutionDispatcher(
            wasmEngine = wasm,
            toyboxEngine = toybox,
            rootShellEngine = root
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_dispatch_root")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
            .build()

        val result = dispatcher.dispatch(task)

        assertThat(wasm.callCount).isEqualTo(0)
        assertThat(toybox.callCount).isEqualTo(0)
        assertThat(root.callCount).isEqualTo(1)
        assertThat(result.stdoutString()).isEqualTo("result from root")
    }

    @Test
    fun testDispatcher_unspecifiedEngineRejected() = runTest {
        val dispatcher = TaskExecutionDispatcher(
            wasmEngine = RecordingEngine("wasm"),
            toyboxEngine = RecordingEngine("toybox"),
            rootShellEngine = RecordingEngine("root")
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("task_unspecified")
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_UNSPECIFIED)
            .build()

        val result = dispatcher.dispatch(task)

        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorCode).isEqualTo("UNSUPPORTED_ENGINE")
    }

    @Test
    fun testConversionToSubmitCellularResultRequest() {
        val result = TaskExecutionResult(
            exitCode = 0,
            stdoutBytes = "computation output".toByteArray(),
            stderrBytes = "diagnostic log".toByteArray(),
            errorCode = "",
            errorMessage = "",
            executionDurationMs = 250L
        )

        val request = result.toSubmitCellularResultRequest(
            nodeId = "node_cellular_01",
            taskId = "task_99",
            runAttempt = 1
        )

        assertThat(request.nodeId).isEqualTo("node_cellular_01")
        assertThat(request.taskId).isEqualTo("task_99")
        assertThat(request.runAttempt).isEqualTo(1)
        assertThat(request.exitCode).isEqualTo(0)
        assertThat(request.stdoutBytes).isEqualTo(ByteString.copyFrom("computation output".toByteArray()))
        assertThat(request.stderrBytes).isEqualTo(ByteString.copyFrom("diagnostic log".toByteArray()))
        assertThat(request.executionDurationMs).isEqualTo(250L)
    }

    @Test
    fun testConversionToTaskExecutionReport() {
        val result = TaskExecutionResult(
            exitCode = 0,
            stdoutBytes = "ok".toByteArray(),
            stderrBytes = ByteArray(0),
            errorCode = "",
            errorMessage = "",
            executionDurationMs = 120L
        )

        val report = result.toTaskExecutionReport(
            taskId = "task_101",
            runAttempt = 2,
            state = TaskState.TASK_STATE_COMPLETED
        )

        assertThat(report.taskId).isEqualTo("task_101")
        assertThat(report.runAttempt).isEqualTo(2)
        assertThat(report.state).isEqualTo(TaskState.TASK_STATE_COMPLETED)
        assertThat(report.exitCode).isEqualTo(0)
        assertThat(report.executionTimeMs).isEqualTo(120L)
    }
}
