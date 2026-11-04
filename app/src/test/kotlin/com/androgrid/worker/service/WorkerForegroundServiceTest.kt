package com.androgrid.worker.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Worker Foreground Service configuration and contract bounds.
 *
 * Traces to: [REQ-OP-002], [REQ-VER-001], [INV-001]
 */
class WorkerForegroundServiceTest {

    @Test
    fun testServiceConstants_adhereToSpecification() {
        assertThat(WorkerForegroundService.NOTIFICATION_ID).isEqualTo(1001)
        assertThat(WorkerForegroundService.CHANNEL_ID).isEqualTo("androgrid_worker_sync_channel")
        assertThat(WorkerForegroundService.ACTION_START_SYNC).isEqualTo("com.androgrid.worker.action.START_SYNC")
        assertThat(WorkerForegroundService.ACTION_STOP_SYNC).isEqualTo("com.androgrid.worker.action.STOP_SYNC")
    }

    @Test
    fun testSyncInterval_enforcesTwoSecondsBound() {
        // Enforces [REQ-OP-002]: The worker node SHALL invoke SyncNodeState every 2.0 seconds
        assertThat(WorkerForegroundService.SYNC_INTERVAL_MS).isEqualTo(2000L)
    }
}
