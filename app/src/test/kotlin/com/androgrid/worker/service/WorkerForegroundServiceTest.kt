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
            completedReports = reports,
            nodeId = "wifi-test-node"
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
            inFlightTasks = inFlight,
            nodeId = "wifi-test-node"
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
            inFlightTasks = inFlight,
            nodeId = "wifi-test-node"
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

    @Test
    fun testWifiSyncTaskProcessor_externalArtifactResolution_andTerminalNotFoundReport() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val reports = ConcurrentLinkedQueue<TaskExecutionReport>()
        val payload = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val okSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val missingSha = "b".repeat(64)

        val executedTasks = mutableListOf<TaskSpec>()
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                executedTasks.add(task)
                return TaskExecutionResult.success("ok".toByteArray(), 5L)
            }
        }
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            completedReports = reports,
            nodeId = "wifi-test-node",
            fetchArtifactRpc = { req ->
                if (req.artifactSha256 == okSha) {
                    com.androgrid.v1.FetchArtifactResponse.newBuilder()
                        .setWasmBytecode(ByteString.copyFrom(payload))
                        .build()
                } else {
                    throw io.grpc.StatusRuntimeException(
                        io.grpc.Status.NOT_FOUND.withDescription("not found")
                    )
                }
            }
        )

        val okTask = TaskSpec.newBuilder()
            .setTaskId("wifi_ext_ok")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(okSha)
            .build()
        val missingTask = TaskSpec.newBuilder()
            .setTaskId("wifi_ext_missing")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(missingSha)
            .build()

        processor.launchTaskIfEligible(this, okTask)?.join()
        processor.launchTaskIfEligible(this, missingTask)?.join()

        assertThat(executedTasks).hasSize(1)
        assertThat(executedTasks.single().wasmBytecode.toByteArray()).isEqualTo(payload)
        assertThat(reports).hasSize(2)
        val missingReport = reports.first { it.taskId == "wifi_ext_missing" }
        assertThat(missingReport.state).isEqualTo(TaskState.TASK_STATE_FAILED)
        assertThat(missingReport.errorCode).isEqualTo("ARTIFACT_NOT_FOUND")
        assertThat(ledger.ledgerSnapshot.value.tasksCompletedCount).isEqualTo(1)
        assertThat(ledger.ledgerSnapshot.value.tasksFailedCount).isEqualTo(1)
    }

    @Test
    fun testWifiSyncTaskProcessor_cancelTask_cancelsActiveJobAndIsIdempotent(): Unit = runTest {
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<TaskExecutionResult>()
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                started.complete(Unit)
                return neverCompletes.await()
            }
        }
        val ledger = InMemoryTaskLedgerRepository()
        val reports = ConcurrentLinkedQueue<TaskExecutionReport>()
        val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            completedReports = reports,
            nodeId = "wifi-test-node",
            activeJobs = activeJobs
        )

        val task = TaskSpec.newBuilder()
            .setTaskId("cancel-target-1")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
            .build()

        val job = processor.launchTaskIfEligible(this, task)
        assertThat(job).isNotNull()
        started.await()
        assertThat(activeJobs.containsKey("cancel-target-1")).isTrue()

        val firstCancel = processor.cancelTask("cancel-target-1")
        val secondCancel = processor.cancelTask("cancel-target-1")
        val unknownCancel = processor.cancelTask("unknown-task-id")
        job!!.join()

        assertThat(firstCancel).isTrue()
        assertThat(secondCancel).isFalse()
        assertThat(unknownCancel).isFalse()
        assertThat(job.isCancelled).isTrue()
        assertThat(activeJobs).isEmpty()
        assertThat(reports).isEmpty()
        assertThat(ledger.ledgerSnapshot.value.tasksFailedCount).isEqualTo(0)
    }

    @Test
    fun testApplySyncResponse_cancelsTasksEvenWhenThermallyThrottled(): Unit = runTest {
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<TaskExecutionResult>()
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                started.complete(Unit)
                return neverCompletes.await()
            }
        }
        val ledger = InMemoryTaskLedgerRepository()
        val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            nodeId = "wifi-test-node",
            activeJobs = activeJobs
        )

        val runningTask = TaskSpec.newBuilder()
            .setTaskId("throttled-cancel-1")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
            .build()
        val newTask = TaskSpec.newBuilder()
            .setTaskId("throttled-skip-2")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
            .build()

        val runningJob = processor.launchTaskIfEligible(this, runningTask)!!
        started.await()

        val response = com.androgrid.v1.SyncNodeStateResponse.newBuilder()
            .addCancelTaskIds("throttled-cancel-1")
            .addDesiredTasks(newTask)
            .build()

        processor.applySyncResponse(this, response, isThrottled = true)
        runningJob.join()

        assertThat(runningJob.isCancelled).isTrue()
        assertThat(activeJobs.containsKey("throttled-skip-2")).isFalse()
    }

    @Test
    fun testWifiSyncTaskProcessor_cancelledScopeBeforeStart_doesNotLeakInFlightOrActiveJobs(): Unit = runTest {
        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                return TaskExecutionResult(
                    exitCode = 0,
                    stdoutBytes = ByteArray(0),
                    stderrBytes = ByteArray(0)
                )
            }
        }
        val ledger = InMemoryTaskLedgerRepository()
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            inFlightTasks = inFlight,
            nodeId = "wifi-test-node",
            activeJobs = activeJobs
        )

        val cancelledScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })
        val task = TaskSpec.newBuilder()
            .setTaskId("pre-cancelled-task")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
            .build()

        val job = processor.launchTaskIfEligible(cancelledScope, task)
        assertThat(job).isNotNull()
        assertThat(job!!.isCancelled).isTrue()
        assertThat(inFlight).isEmpty()
        assertThat(activeJobs).isEmpty()
    }

    @Test
    fun testWifiSyncTaskProcessor_consecutiveRunAttempts_serializeViaCancelAndJoin(): Unit = runTest {
        val attempt1Started = CompletableDeferred<Unit>()
        val attempt1CleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempt2SawAttempt1CleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeExecutions = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val engine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                val current = activeExecutions.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, current) }
                try {
                    if (task.runAttempt == 1) {
                        attempt1Started.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                kotlinx.coroutines.delay(30)
                                attempt1CleanedUp.set(true)
                            }
                        }
                    } else {
                        attempt2SawAttempt1CleanedUp.set(attempt1CleanedUp.get())
                    }
                    return TaskExecutionResult(
                        exitCode = 0,
                        stdoutBytes = ByteArray(0),
                        stderrBytes = ByteArray(0)
                    )
                } finally {
                    activeExecutions.decrementAndGet()
                }
            }
        }

        val ledger = InMemoryTaskLedgerRepository()
        val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        val processor = WifiSyncTaskProcessor(
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            ledgerRepository = ledger,
            nodeId = "wifi-test-node",
            activeJobs = activeJobs
        )

        val attempt1 = TaskSpec.newBuilder()
            .setTaskId("serial-task")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
            .build()
        val attempt2 = attempt1.toBuilder().setRunAttempt(2).build()

        val job1 = processor.launchTaskIfEligible(this, attempt1)!!
        attempt1Started.await()

        val job2 = processor.launchTaskIfEligible(this, attempt2)!!
        job2.join()

        assertThat(job1.isCancelled).isTrue()
        assertThat(attempt1CleanedUp.get()).isTrue()
        assertThat(attempt2SawAttempt1CleanedUp.get()).isTrue()
        assertThat(maxConcurrent.get()).isEqualTo(1)
        assertThat(activeJobs).isEmpty()
    }
}
