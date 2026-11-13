package com.androgrid.worker.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit and Robolectric tests verifying [TaskLedgerRepository], FIFO deduplication,
 * ring buffer bounds, cold-start hydration, corruption recovery, and data hygiene.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [INV-004], [NFR-SEC-001], ADR-008
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskLedgerRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesTaskLedgerRepository.resetForTesting()
        context.getSharedPreferences(
            SharedPreferencesTaskLedgerRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @After
    fun tearDown() {
        SharedPreferencesTaskLedgerRepository.resetForTesting()
    }

    private fun entry(
        taskId: String,
        runAttempt: Int = 1,
        status: TaskLedgerStatus = TaskLedgerStatus.COMPLETED,
        route: String = "WIFI_LAN",
        exitCode: Int = if (status == TaskLedgerStatus.FAILED) 1 else 0,
        errorCode: String = if (status == TaskLedgerStatus.FAILED) "WASM_TRAP" else "",
        durationMs: Long = 42L,
        completedAtEpochMs: Long = 1794543569786L
    ) = TaskLedgerEntry(
        taskId = taskId,
        runAttempt = runAttempt,
        route = route,
        status = status,
        exitCode = exitCode,
        errorCode = errorCode,
        executionDurationMs = durationMs,
        completedAtEpochMs = completedAtEpochMs
    )

    @Test
    fun testStatusCounters_onlyTerminalSuccessAndFailureIncrementCounts() = runTest {
        val repo = InMemoryTaskLedgerRepository()

        assertThat(repo.recordTaskExecution(entry("t1", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        assertThat(repo.recordTaskExecution(entry("t2", 1, TaskLedgerStatus.FAILED))).isTrue()
        assertThat(repo.recordTaskExecution(entry("t3", 1, TaskLedgerStatus.STALE))).isTrue()
        assertThat(repo.recordTaskExecution(entry("t4", 1, TaskLedgerStatus.SUBMISSION_FAILED))).isTrue()

        val snapshot = repo.ledgerSnapshot.value
        assertThat(snapshot.tasksCompletedCount).isEqualTo(1)
        assertThat(snapshot.tasksFailedCount).isEqualTo(1)
        assertThat(snapshot.recentEntries).hasSize(4)
        assertThat(snapshot.recentEntries.first().taskId).isEqualTo("t4")
    }

    @Test
    fun testDedup_terminalStatusesDeduplicateWhileSubmissionFailedRemainsRetryable() = runTest {
        val repo = InMemoryTaskLedgerRepository()

        // SUBMISSION_FAILED is non-terminal: must NOT populate seenKeys
        assertThat(repo.recordTaskExecution(entry("t1", 1, TaskLedgerStatus.SUBMISSION_FAILED))).isTrue()
        assertThat(repo.isRecorded("t1", 1)).isFalse()

        // Subsequent terminal COMPLETED for the same (taskId, runAttempt) must succeed
        assertThat(repo.recordTaskExecution(entry("t1", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        assertThat(repo.isRecorded("t1", 1)).isTrue()

        // Duplicate terminal recording for (t1, 1) must be skipped
        assertThat(repo.recordTaskExecution(entry("t1", 1, TaskLedgerStatus.COMPLETED))).isFalse()
        assertThat(repo.recordTaskExecution(entry("t1", 1, TaskLedgerStatus.FAILED))).isFalse()

        // Higher runAttempt for same taskId must be accepted
        assertThat(repo.recordTaskExecution(entry("t1", 2, TaskLedgerStatus.COMPLETED))).isTrue()

        val snapshot = repo.ledgerSnapshot.value
        assertThat(snapshot.tasksCompletedCount).isEqualTo(2)
        assertThat(snapshot.tasksFailedCount).isEqualTo(0)
    }

    @Test
    fun testRingBufferAndFifoDedupBounds_20RecentEntriesAnd512DedupKeys() = runTest {
        val repo = InMemoryTaskLedgerRepository()

        // Record 512 terminal tasks
        for (i in 1..512) {
            assertThat(repo.recordTaskExecution(entry("task_$i", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        }

        // UI ring buffer is capped at 20 newest entries
        assertThat(repo.ledgerSnapshot.value.recentEntries).hasSize(20)
        assertThat(repo.ledgerSnapshot.value.recentEntries.first().taskId).isEqualTo("task_512")

        // Task #1 was evicted from UI ring buffer after task #21, yet must STILL be deduplicated
        assertThat(repo.isRecorded("task_1", 1)).isTrue()
        assertThat(repo.recordTaskExecution(entry("task_1", 1, TaskLedgerStatus.COMPLETED))).isFalse()

        // Task #513 evicts oldest dedup key (task_1:1) via FIFO while keeping task_2:1
        assertThat(repo.recordTaskExecution(entry("task_513", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        assertThat(repo.isRecorded("task_1", 1)).isFalse()
        assertThat(repo.isRecorded("task_2", 1)).isTrue()
        assertThat(repo.isRecorded("task_513", 1)).isTrue()
        assertThat(repo.ledgerSnapshot.value.tasksCompletedCount).isEqualTo(513)
    }

    @Test
    fun testSharedPreferencesPersistence_roundTripAndColdStartHydrationGuard() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo1 = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = TestScope(dispatcher)
        )
        assertThat(repo1.recordTaskExecution(entry("t_persist", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        assertThat(repo1.recordTaskExecution(entry("t_fail", 2, TaskLedgerStatus.FAILED))).isTrue()

        // New repo with un-run background hydrationScope (StandardTestDispatcher queued but not run yet):
        // Immediate recordTaskExecution() must still run ensureHydratedLocked() before mutating!
        val repo2Scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo2 = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = repo2Scope
        )
        assertThat(repo2.recordTaskExecution(entry("t_new", 1, TaskLedgerStatus.COMPLETED))).isTrue()

        val snapshot = repo2.ledgerSnapshot.value
        assertThat(snapshot.tasksCompletedCount).isEqualTo(2)
        assertThat(snapshot.tasksFailedCount).isEqualTo(1)
        assertThat(repo2.isRecorded("t_persist", 1)).isTrue()
        assertThat(repo2.isRecorded("t_fail", 2)).isTrue()
        assertThat(repo2.isRecorded("t_new", 1)).isTrue()
    }

    @Test
    fun testCorruptOrIncompatibleJson_resetsCleanlyWithoutCrashing() = runTest {
        val prefs = context.getSharedPreferences(
            SharedPreferencesTaskLedgerRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        // Case 1: Corrupt JSON
        prefs.edit().putString(SharedPreferencesTaskLedgerRepository.KEY_LEDGER_JSON, "{not-valid-json").commit()

        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoCorrupt = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = TestScope(dispatcher)
        )
        assertThat(repoCorrupt.isRecorded("any", 1)).isFalse()
        assertThat(repoCorrupt.ledgerSnapshot.value).isEqualTo(TaskLedgerSnapshot.EMPTY)

        // Case 2: Unsupported schema version
        prefs.edit().putString(
            SharedPreferencesTaskLedgerRepository.KEY_LEDGER_JSON,
            """{"version":99,"completedCount":10}"""
        ).commit()

        val repoVersion = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = TestScope(dispatcher)
        )
        assertThat(repoVersion.recordTaskExecution(entry("t_clean", 1, TaskLedgerStatus.COMPLETED))).isTrue()
        assertThat(repoVersion.ledgerSnapshot.value.tasksCompletedCount).isEqualTo(1)
    }

    @Test
    fun testDataHygiene_stdoutAndStderrKeysAreExcludedFromPersistedJson() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = TestScope(dispatcher)
        )
        repo.recordTaskExecution(entry("t_hygiene", 1, TaskLedgerStatus.COMPLETED))

        val rawJson = context.getSharedPreferences(
            SharedPreferencesTaskLedgerRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).getString(SharedPreferencesTaskLedgerRepository.KEY_LEDGER_JSON, "") ?: ""

        assertThat(rawJson).doesNotContain("stdout")
        assertThat(rawJson).doesNotContain("stderr")
        assertThat(rawJson).contains("\"version\":1")
    }

    @Test
    fun testConcurrentWrites_mutexSerializesAllTasksWithoutLostUpdates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = SharedPreferencesTaskLedgerRepository(
            context = context,
            ioDispatcher = dispatcher,
            hydrationScope = TestScope(dispatcher)
        )

        val jobs = (1..50).map { idx ->
            async {
                repo.recordTaskExecution(entry("concurrent_$idx", 1, TaskLedgerStatus.COMPLETED))
            }
        }
        val results = jobs.awaitAll()
        assertThat(results.count { it }).isEqualTo(50)
        assertThat(repo.ledgerSnapshot.value.tasksCompletedCount).isEqualTo(50)
    }
}
