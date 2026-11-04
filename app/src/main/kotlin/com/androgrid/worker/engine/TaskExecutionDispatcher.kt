package com.androgrid.worker.engine

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskSpec

/**
 * Central task execution dispatcher routing tasks to corresponding isolated engines.
 * Measures wall-clock execution duration and enforces engine policy boundaries.
 *
 * Traces to: [INV-006], [REQ-OP-002], [REQ-OP-004]
 */
class TaskExecutionDispatcher(
    private val wasmEngine: ComputeEngine = WasmComputeEngine(),
    private val toyboxEngine: ComputeEngine = ToyboxUserEngine(),
    private val rootShellEngine: ComputeEngine = RootShellEngine()
) {

    /**
     * Dispatches task to designated engine and measures execution duration.
     *
     * @param task task specification to execute.
     * @return TaskExecutionResult with captured execution artifacts.
     */
    suspend fun dispatch(task: TaskSpec): TaskExecutionResult {
        val startTimeMs = System.currentTimeMillis()

        val engine = when (task.engine) {
            ExecutionEngine.EXECUTION_ENGINE_WASM_WASI -> wasmEngine
            ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER -> toyboxEngine
            ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL -> rootShellEngine
            else -> null
        }

        if (engine == null) {
            val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
            return TaskExecutionResult.failure(
                exitCode = -1,
                errorCode = "UNSUPPORTED_ENGINE",
                errorMessage = "Unsupported execution engine: ${task.engine}",
                durationMs = durationMs
            )
        }

        val rawResult = engine.execute(task)
        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)

        // Ensure duration is recorded accurately from dispatcher measurement
        return if (rawResult.executionDurationMs == 0L) {
            rawResult.copy(executionDurationMs = durationMs)
        } else {
            rawResult
        }
    }
}
