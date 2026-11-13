package com.androgrid.worker.ui

import android.content.pm.ApplicationInfo
import android.util.Log
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.androgrid.worker.AndroGridApplication
import com.androgrid.worker.telemetry.SharedPreferencesTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerEntry
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric lifecycle and configuration-change tests for [MainActivity] with [AndroGridApplication].
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [INV-003], [NFR-POW-001], ADR-008
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AndroGridApplication::class)
class MainActivityTest {

    private lateinit var app: AndroGridApplication

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as AndroGridApplication
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        SharedPreferencesTaskLedgerRepository.resetForTesting()
    }

    @After
    fun tearDown() {
        SharedPreferencesTaskLedgerRepository.resetForTesting()
    }

    @Test
    fun testActivityRecreate_preservesLedgerCountsAndComputeActiveState() {
        runBlocking {
            // Record a completed and a failed task in the durable application ledger
            app.taskLedgerRepository.recordTaskExecution(
                TaskLedgerEntry(
                    taskId = "task-rot-1",
                    runAttempt = 1,
                    route = "WIFI_LAN",
                    status = TaskLedgerStatus.COMPLETED,
                    exitCode = 0,
                    errorCode = "",
                    executionDurationMs = 20L,
                    completedAtEpochMs = 1000L
                )
            )
            app.taskLedgerRepository.recordTaskExecution(
                TaskLedgerEntry(
                    taskId = "task-rot-2",
                    runAttempt = 1,
                    route = "CELLULAR_WAN",
                    status = TaskLedgerStatus.FAILED,
                    exitCode = 2,
                    errorCode = "ERR_TEST",
                    executionDurationMs = 15L,
                    completedAtEpochMs = 2000L
                )
            )
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        activity.getDashboardViewModel().startParticipation()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertThat(activity.getDashboardViewModel().uiState.value.isComputeActive).isTrue()
        assertThat(activity.getDashboardViewModel().uiState.value.tasksCompletedCount).isEqualTo(1)
        assertThat(activity.getDashboardViewModel().uiState.value.tasksFailedCount).isEqualTo(1)

        // Simulate configuration change (rotation) via Robolectric recreate()
        controller.recreate()
        val recreated = controller.get()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val recreatedState = recreated.getDashboardViewModel().uiState.value
        assertThat(recreatedState.isComputeActive).isTrue()
        assertThat(recreatedState.tasksCompletedCount).isEqualTo(1)
        assertThat(recreatedState.tasksFailedCount).isEqualTo(1)
        assertThat(recreatedState.recentEntries).hasSize(2)

        // Clean up active participation
        recreated.getDashboardViewModel().stopParticipation()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
