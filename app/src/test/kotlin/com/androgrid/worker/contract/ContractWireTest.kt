package com.androgrid.worker.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests verifying Android client contract bounds and architectural constants.
 *
 * Traces to: [INV-003], [INV-005], [NFR-POW-001], [NFR-PERF-001]
 */
class ContractWireTest {

    @Test
    fun testFrameSizeBound_strictlyEnforces16MB() {
        val expectedBytes = 16L * 1024L * 1024L
        assertThat(ContractConstants.MAX_FRAME_SIZE_BYTES).isEqualTo(expectedBytes)
    }

    @Test
    fun testMaxCellularTaskDuration_cappedAt180Seconds() {
        assertThat(ContractConstants.MAX_CELLULAR_TASK_DURATION_SECONDS).isEqualTo(180)
    }

    @Test
    fun testRadioSleepTermination_enforces200MsBoundary() {
        assertThat(ContractConstants.RADIO_SLEEP_TERMINATION_DEADLINE_MS).isEqualTo(200L)
    }
}
