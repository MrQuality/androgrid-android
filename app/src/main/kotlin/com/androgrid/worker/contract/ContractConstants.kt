package com.androgrid.worker.contract

/**
 * Cluster-wide constants and architectural boundary constraints.
 *
 * Traces to: [INV-003], [INV-005], [NFR-POW-001], [NFR-PERF-001]
 */
object ContractConstants {
    /**
     * Maximum gRPC frame size in bytes (16 MB).
     * Traces to: [NFR-PERF-001]
     */
    const val MAX_FRAME_SIZE_BYTES: Long = 16L * 1024L * 1024L

    /**
     * Maximum duration in seconds for a cellular spot task lease (180s).
     * Traces to: [INV-005]
     */
    const val MAX_CELLULAR_TASK_DURATION_SECONDS: Int = 180

    /**
     * Baseband radio sleep socket termination deadline in milliseconds (200 ms).
     * Traces to: [INV-003], [NFR-POW-001]
     */
    const val RADIO_SLEEP_TERMINATION_DEADLINE_MS: Long = 200L
}
