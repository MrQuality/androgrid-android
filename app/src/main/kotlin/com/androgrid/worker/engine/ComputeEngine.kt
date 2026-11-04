package com.androgrid.worker.engine

import com.androgrid.v1.TaskSpec

/**
 * Strategy interface defining sandboxed compute payload execution on Android.
 *
 * Traces to: [INV-006], [REQ-VER-003], [NFR-PERF-001]
 */
interface ComputeEngine {
    /**
     * Executes specified compute task in isolated runtime sandbox.
     *
     * @param task task specification including bytecode/command, environment, and timeout.
     * @return standardized TaskExecutionResult with captured streams and execution metrics.
     */
    suspend fun execute(task: TaskSpec): TaskExecutionResult
}
