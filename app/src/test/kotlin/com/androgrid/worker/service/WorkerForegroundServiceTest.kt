package com.androgrid.worker.service

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.TaskExecutionReport
import com.androgrid.v1.TaskSpec
import com.androgrid.v1.TaskState
import com.androgrid.worker.engine.ComputeEngine
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.engine.TaskExecutionResult
import com.androgrid.worker.telemetry.InMemoryTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun testWifiSyncTaskProcessor_recordsCompletedAndFailedEntriesWithWifiLanRoute() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val reports = ConcurrentLinkedQueue<TaskExecutionReport>()
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                return if (task.taskId == "wifi_ok") {
                    TaskExecutionResult(
                        exitCode = 0,
                        errorCode = "",
                        stdoutBytes = ByteArray(0),
                        stderrBytes = ByteArray(0),
                        executionDurationMs = 12L
                    )
                } else {
                    TaskExecutionResult(
                        exitCode = 2,
                        errorCode = "WASM_ERROR",
                        stdoutBytes = ByteArray(0),
                        stderrBytes = ByteArray(0),
                        executionDurationMs = 8L
                    )
                }
            }
        }
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            completedReports = reports
        )

        val okTask = TaskSpec.newBuilder()
            .setTaskId("wifi_ok")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("a".toByteArray()))
            .build()
        val failTask = TaskSpec.newBuilder()
            .setTaskId("wifi_fail")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("b".toByteArray()))
            .build()

        processor.launchTaskIfEligible(this, okTask)?.join()
        processor.launchTaskIfEligible(this, failTask)?.join()

        val snap = ledger.ledgerSnapshot.value
        assertThat(snap.tasksCompletedCount).isEqualTo(1)
        assertThat(snap.tasksFailedCount).isEqualTo(1)
        assertThat(snap.recentEntries.map { it.route }).containsExactly("WIFI_LAN", "WIFI_LAN")
        assertThat(snap.recentEntries.first().status).isEqualTo(TaskLedgerStatus.FAILED)
        assertThat(snap.recentEntries.last().status).isEqualTo(TaskLedgerStatus.COMPLETED)
        assertThat(reports.map { it.state }).containsExactly(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_FAILED
        ).inOrder()
    }

    @Test
    fun testWifiSyncTaskProcessor_skipsAlreadyRecordedAndConcurrentInFlightDuplicates() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val dispatchCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val slowEngine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                dispatchCount.incrementAndGet()
                gate.await()
                return TaskExecutionResult(
                    exitCode = 0,
                    errorCode = "",
                    stdoutBytes = ByteArray(0),
                    stderrBytes = ByteArray(0),
                    executionDurationMs = 5L
                )
            }
        }
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = slowEngine),
            ledgerRepository = ledger,
            inFlightTasks = inFlight
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("wifi_dup")
            .setRunAttempt(3)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("x".toByteArray()))
            .build()

        // First launch enters in-flight state
        val job1 = processor.launchTaskIfEligible(this, task)
        assertThat(job1).isNotNull()
        assertThat(inFlight).contains("wifi_dup:3")

        // Second launch while in-flight is rejected
        val job2 = processor.launchTaskIfEligible(this, task)
        assertThat(job2).isNull()

        // Complete first job
        gate.complete(Unit)
        job1!!.join()
        assertThat(inFlight).isEmpty()
        assertThat(dispatchCount.get()).isEqualTo(1)

        // Third launch after completion is rejected by ledgerRepository.isRecorded()
        val job3 = processor.launchTaskIfEligible(this, task)
        assertThat(job3).isNull()
        assertThat(dispatchCount.get()).isEqualTo(1)
    }

    @Test
    fun testWifiSyncTaskProcessor_cancellationDoesNotInflateFailedCountAndReleasesLease() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val started = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                started.complete(Unit)
                neverFinishes.await()
                return TaskExecutionResult(
                    exitCode = 0,
                    errorCode = "",
                    stdoutBytes = ByteArray(0),
                    stderrBytes = ByteArray(0),
                    executionDurationMs = 0L
                )
            }
        }
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            inFlightTasks = inFlight
        )
        val task = TaskSpec.newBuilder()
            .setTaskId("wifi_cancel")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("c".toByteArray()))
            .build()

        val job = processor.launchTaskIfEligible(this, task)!!
        started.await()
        job.cancelAndJoin()

        assertThat(inFlight).isEmpty()
        assertThat(ledger.ledgerSnapshot.value.tasksFailedCount).isEqualTo(0)
        assertThat(ledger.isRecorded("wifi_cancel", 1)).isFalse()
    }
}
