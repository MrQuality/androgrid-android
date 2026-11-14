package com.androgrid.worker.service

import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import com.androgrid.v1.ClaimCellularTaskRequest
import com.androgrid.v1.ClaimCellularTaskResponse
import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.SubmitCellularResultRequest
import com.androgrid.v1.SubmitCellularResultResponse
import com.androgrid.v1.TaskSpec
import com.androgrid.worker.capability.BatteryStatus
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.RootChecker
import com.androgrid.worker.capability.SystemInfoProvider
import com.androgrid.worker.engine.ComputeEngine
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.engine.TaskExecutionResult
import com.androgrid.worker.network.CellularRpcInvoker
import com.androgrid.worker.network.CellularTransportClient
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.InMemoryTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.androgrid.worker.telemetry.ThermalSafetyGuard
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for Cellular Background Spot Worker lifecycle and battery preconditions.
 *
 * Traces to: [INV-001], [INV-003], [REQ-OP-003], [REQ-OP-004], [NFR-POW-001]
 */
class CellularSpotWorkerTest {

    private class MockChannel : ManagedChannel() {
        val shutdownCalled = AtomicBoolean(false)
        override fun shutdown(): ManagedChannel = this
        override fun shutdownNow(): ManagedChannel {
            shutdownCalled.set(true)
            return this
        }
        override fun isShutdown(): Boolean = shutdownCalled.get()
        override fun isTerminated(): Boolean = shutdownCalled.get()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun authority(): String = "localhost:443"
        override fun <RequestT : Any?, ResponseT : Any?> newCall(
            methodDescriptor: io.grpc.MethodDescriptor<RequestT, ResponseT>?,
            callOptions: io.grpc.CallOptions?
        ): io.grpc.ClientCall<RequestT, ResponseT> {
            throw UnsupportedOperationException()
        }
    }

    private class FakeBatteryProvider(private var status: BatteryStatus) : BatteryStatusProvider {
        fun setStatus(newStatus: BatteryStatus) {
            status = newStatus
        }
        override fun getBatteryStatus(): BatteryStatus = status
    }

    private class FakeThermalListener(
        override var currentThermalStatus: Int = 0
    ) : DeviceThermalStatusListener {
        private val _flow = MutableStateFlow(currentThermalStatus)
        override val thermalStatusFlow: StateFlow<Int> = _flow
        override fun startListening() {}
        override fun stopListening() {}
    }

    private class FakeRootChecker : RootChecker {
        override fun isDeviceRooted(): Boolean = false
    }

    private class FakeSystemInfo : SystemInfoProvider {
        override fun availableProcessors(): Int = 4
        override fun totalMemoryBytes(): Long = 4L * 1024L * 1024L * 1024L
        override fun osVersion(): String = "14"
        override fun supportedAbi(): String = "arm64-v8a"
    }

    private class FakeCellularRpc(
        var claimResponse: Result<ClaimCellularTaskResponse>,
        var submitResponse: Result<SubmitCellularResultResponse> = Result.success(
            SubmitCellularResultResponse.newBuilder().setAccepted(true).build()
        ),
        var fetchResponse: Result<com.androgrid.v1.FetchArtifactResponse> = Result.failure(
            StatusRuntimeException(Status.NOT_FOUND)
        )
    ) : CellularRpcInvoker {
        var lastClaimRequest: ClaimCellularTaskRequest? = null
        var lastSubmitRequest: SubmitCellularResultRequest? = null
        var lastClaimChannel: ManagedChannel? = null
        var lastFetchChannel: ManagedChannel? = null
        var lastSubmitChannel: ManagedChannel? = null

        override suspend fun invokeClaim(
            channel: ManagedChannel,
            request: ClaimCellularTaskRequest
        ): ClaimCellularTaskResponse {
            lastClaimChannel = channel
            lastClaimRequest = request
            return claimResponse.getOrThrow()
        }

        override suspend fun invokeFetchArtifact(
            channel: ManagedChannel,
            request: com.androgrid.v1.FetchArtifactRequest
        ): com.androgrid.v1.FetchArtifactResponse {
            lastFetchChannel = channel
            return fetchResponse.getOrThrow()
        }

        override suspend fun invokeSubmit(
            channel: ManagedChannel,
            request: SubmitCellularResultRequest
        ): SubmitCellularResultResponse {
            lastSubmitChannel = channel
            lastSubmitRequest = request
            return submitResponse.getOrThrow()
        }
    }

    private class EchoEngine : ComputeEngine {
        var executedTask: TaskSpec? = null

        override suspend fun execute(task: TaskSpec): TaskExecutionResult {
            executedTask = task
            return TaskExecutionResult.success(
                stdoutBytes = "computation output for ${task.taskId}".toByteArray(),
                durationMs = 50L
            )
        }
    }

    @Test
    fun testLowBatteryWithoutCharging_skipsClaim() = runTest {
        // Enforces [REQ-OP-003]: battery level >= 20% or charging
        val batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 15, isCharging = false))
        val rpc = FakeCellularRpc(Result.success(ClaimCellularTaskResponse.newBuilder().setTaskAvailable(false).build()))
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = dispatcher,
            channelProvider = { MockChannel() }
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(rpc.lastClaimRequest).isNull() // Claim was bypassed
    }

    @Test
    fun testLowBatteryWithCharging_allowsClaim() = runTest {
        // Enforces [REQ-OP-003]: Charging overrides low battery constraint
        val batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 15, isCharging = true))
        val rpc = FakeCellularRpc(Result.success(ClaimCellularTaskResponse.newBuilder().setTaskAvailable(false).build()))
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = dispatcher,
            channelProvider = { MockChannel() }
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(rpc.lastClaimRequest).isNotNull()
        assertThat(rpc.lastClaimRequest?.isCharging).isTrue()
    }

    @Test
    fun testSuccessfulExecutionLifecycle_claimsExecutesAndSubmits() = runTest {
        // Enforces [REQ-OP-003], [REQ-OP-004], [INV-003]
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_100")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("payload".toByteArray()))
            .build()

        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder()
                    .setTaskAvailable(true)
                    .setTask(task)
                    .build()
            )
        )
        val batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false))
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val echoEngine = EchoEngine()
        val dispatcher = TaskExecutionDispatcher(wasmEngine = echoEngine)

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = dispatcher,
            channelProvider = { MockChannel() }
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(echoEngine.executedTask?.taskId).isEqualTo("task_cell_100")
        assertThat(rpc.lastSubmitRequest).isNotNull()
        assertThat(rpc.lastSubmitRequest?.taskId).isEqualTo("task_cell_100")
        assertThat(rpc.lastSubmitRequest?.runAttempt).isEqualTo(1)
        assertThat(rpc.lastSubmitRequest?.stdoutBytes?.toStringUtf8())
            .isEqualTo("computation output for task_cell_100")
    }

    @Test
    fun testPreemptedStaleTaskSubmission_returnsSuccessToReturnWorkerToIdle() = runTest {
        // Enforces [INV-004]: Preempted task receives TASK_ATTEMPT_STALE; worker returns to idle
        val task = TaskSpec.newBuilder()
            .setTaskId("task_stale")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .build()

        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder()
                    .setTaskAvailable(true)
                    .setTask(task)
                    .build()
            ),
            submitResponse = Result.failure(
                StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("TASK_ATTEMPT_STALE"))
            )
        )
        val batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 90, isCharging = true))
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = dispatcher,
            channelProvider = { MockChannel() }
        )

        val result = workerLogic.executeSpotTask()

        // Worker handles stale attempt gracefully by returning success to complete the WorkManager job
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun testClaimNetworkFailure_returnsRetry() = runTest {
        val rpc = FakeCellularRpc(
            claimResponse = Result.failure(StatusRuntimeException(Status.UNAVAILABLE))
        )
        val batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 50, isCharging = false))
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = dispatcher,
            channelProvider = { MockChannel() }
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun testHighBatteryTemperature_postponesExecutionWithRetry() = runTest {
        // Enforces [INV-003], [NFR-POW-001]: Battery temp > 38 C postpones execution
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).build())
        )
        val batteryProvider = FakeBatteryProvider(
            BatteryStatus(levelPercent = 80, isCharging = false, temperatureCelsius = 39)
        )
        val thermalListener = FakeThermalListener(currentThermalStatus = 0)
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            thermalListener = thermalListener,
            thermalGuard = ThermalSafetyGuard()
        )

        val result = workerLogic.executeSpotTask()

        // Postpones execution with retry, bypassing RPC claim
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(rpc.lastClaimRequest).isNull()
    }

    @Test
    fun testElevatedThermalStatus_postponesExecutionWithRetry() = runTest {
        // Enforces [INV-003], [NFR-POW-001]: OS thermal status >= 3 postpones execution
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).build())
        )
        val batteryProvider = FakeBatteryProvider(
            BatteryStatus(levelPercent = 80, isCharging = false, temperatureCelsius = 30)
        )
        val thermalListener = FakeThermalListener(currentThermalStatus = 3) // SEVERE throttling
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            thermalListener = thermalListener,
            thermalGuard = ThermalSafetyGuard()
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(rpc.lastClaimRequest).isNull()
    }

    @Test
    fun testNominalThermalConditions_claimsTaskWithTelemetryInRequest() = runTest {
        // Enforces [REQ-OP-003]: Telemetry is wired into ClaimCellularTaskRequest
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(false).build()
            )
        )
        val batteryProvider = FakeBatteryProvider(
            BatteryStatus(levelPercent = 80, isCharging = false, temperatureCelsius = 32)
        )
        val thermalListener = FakeThermalListener(currentThermalStatus = 1) // LIGHT throttling
        val client = CellularTransportClient(rpcInvoker = rpc)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())

        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = batteryProvider,
            capabilityProvider = capabilityProvider,
            cellularClient = client,
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            thermalListener = thermalListener,
            thermalGuard = ThermalSafetyGuard()
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(rpc.lastClaimRequest).isNotNull()
        assertThat(rpc.lastClaimRequest?.batteryTemperatureCelsius).isEqualTo(32)
        assertThat(rpc.lastClaimRequest?.thermalStatus).isEqualTo(1)
        assertThat(rpc.lastClaimRequest?.capabilities?.batteryTemperatureCelsius).isEqualTo(32)
        assertThat(rpc.lastClaimRequest?.capabilities?.thermalStatus).isEqualTo(1)
    }

    @Test
    fun testWorkRequestConfiguration_enforcesExponentialBackoffCriteria() {
        val request = CellularSpotWorker.buildWorkRequest()
        assertThat(request.workSpec.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)
    }

    @Test
    fun testSuccessfulExecution_recordsCompletedInLedger() = runTest {
        // Traces to: [REQ-OP-003], [INV-004], ADR-008
        val ledger = InMemoryTaskLedgerRepository()
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_ok")
            .setRunAttempt(2)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("payload".toByteArray()))
            .build()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            )
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            ledgerRepository = ledger
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val snap = ledger.ledgerSnapshot.value
        assertThat(snap.tasksCompletedCount).isEqualTo(1)
        assertThat(snap.tasksFailedCount).isEqualTo(0)
        assertThat(snap.recentEntries.first().status).isEqualTo(TaskLedgerStatus.COMPLETED)
        assertThat(snap.recentEntries.first().route).isEqualTo("CELLULAR_WAN")
    }

    @Test
    fun testFailedExecution_recordsFailedInLedger() = runTest {
        // Traces to: [REQ-OP-003], ADR-008
        val ledger = InMemoryTaskLedgerRepository()
        val failingEngine = object : ComputeEngine {
            override suspend fun execute(task: TaskSpec): TaskExecutionResult {
                return TaskExecutionResult(
                    exitCode = 1,
                    errorCode = "WASM_TRAP",
                    stdoutBytes = ByteArray(0),
                    stderrBytes = ByteArray(0),
                    executionDurationMs = 15L
                )
            }
        }
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_fail")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("payload".toByteArray()))
            .build()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            )
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = failingEngine),
            channelProvider = { MockChannel() },
            ledgerRepository = ledger
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val snap = ledger.ledgerSnapshot.value
        assertThat(snap.tasksCompletedCount).isEqualTo(0)
        assertThat(snap.tasksFailedCount).isEqualTo(1)
        assertThat(snap.recentEntries.first().status).isEqualTo(TaskLedgerStatus.FAILED)
        assertThat(snap.recentEntries.first().errorCode).isEqualTo("WASM_TRAP")
    }

    @Test
    fun testPreemptedStaleTask_recordsStaleInLedger() = runTest {
        // Traces to: [INV-004], ADR-008
        val ledger = InMemoryTaskLedgerRepository()
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_stale")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("payload".toByteArray()))
            .build()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            ),
            submitResponse = Result.failure(
                StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("TASK_ATTEMPT_STALE"))
            )
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            ledgerRepository = ledger
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val snap = ledger.ledgerSnapshot.value
        assertThat(snap.tasksCompletedCount).isEqualTo(0)
        assertThat(snap.tasksFailedCount).isEqualTo(0)
        assertThat(snap.recentEntries.first().status).isEqualTo(TaskLedgerStatus.STALE)
    }

    @Test
    fun testSubmitNetworkFailure_recordsSubmissionFailedAndAllowsRetryCompletion() = runTest {
        // Traces to: [REQ-OP-003], [INV-004], ADR-008 (RSK-DEDUP-02)
        val ledger = InMemoryTaskLedgerRepository()
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_retry")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setWasmBytecode(ByteString.copyFrom("payload".toByteArray()))
            .build()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            ),
            submitResponse = Result.failure(StatusRuntimeException(Status.UNAVAILABLE))
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = EchoEngine()),
            channelProvider = { MockChannel() },
            ledgerRepository = ledger
        )

        val firstResult = workerLogic.executeSpotTask()

        assertThat(firstResult).isEqualTo(ListenableWorker.Result.retry())
        assertThat(ledger.ledgerSnapshot.value.recentEntries.first().status)
            .isEqualTo(TaskLedgerStatus.SUBMISSION_FAILED)
        assertThat(ledger.isRecorded("task_cell_retry", 1)).isFalse()

        // Subsequent retry succeeds and is NOT blocked by prior SUBMISSION_FAILED
        rpc.submitResponse = Result.success(
            SubmitCellularResultResponse.newBuilder().setAccepted(true).build()
        )
        val secondResult = workerLogic.executeSpotTask()

        assertThat(secondResult).isEqualTo(ListenableWorker.Result.success())
        val snap = ledger.ledgerSnapshot.value
        assertThat(snap.tasksCompletedCount).isEqualTo(1)
        assertThat(snap.recentEntries.first().status).isEqualTo(TaskLedgerStatus.COMPLETED)
    }

    @Test
    fun testCellularSpotWorker_externalArtifactSuccess_fetchesOnSameChannelAndExecutes() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val payload = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_ext_ok")
            .setRunAttempt(1)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(sha)
            .build()

        val channels = mutableListOf<MockChannel>()
        val engine = EchoEngine()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            ),
            fetchResponse = Result.success(
                com.androgrid.v1.FetchArtifactResponse.newBuilder()
                    .setWasmBytecode(ByteString.copyFrom(payload))
                    .build()
            )
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            channelProvider = { MockChannel().also { channels.add(it) } },
            ledgerRepository = ledger
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(rpc.lastClaimChannel).isSameInstanceAs(rpc.lastFetchChannel)
        assertThat(channels).hasSize(2)
        assertThat(channels.all { it.shutdownCalled.get() }).isTrue()
        assertThat(engine.executedTask?.wasmBytecode?.toByteArray()).isEqualTo(payload)
        assertThat(ledger.ledgerSnapshot.value.tasksCompletedCount).isEqualTo(1)
    }

    @Test
    fun testCellularSpotWorker_externalArtifactNotFound_submitsTerminalFailureOnSameChannelAndDoesNotRetry() = runTest {
        val ledger = InMemoryTaskLedgerRepository()
        val sha = "a".repeat(64)
        val task = TaskSpec.newBuilder()
            .setTaskId("task_cell_ext_missing")
            .setRunAttempt(2)
            .setEngine(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
            .setArtifactSha256(sha)
            .build()

        val channels = mutableListOf<MockChannel>()
        val engine = EchoEngine()
        val rpc = FakeCellularRpc(
            claimResponse = Result.success(
                ClaimCellularTaskResponse.newBuilder().setTaskAvailable(true).setTask(task).build()
            ),
            fetchResponse = Result.failure(
                StatusRuntimeException(Status.NOT_FOUND.withDescription("artifact missing"))
            )
        )
        val workerLogic = CellularSpotWorkerLogic(
            nodeId = "test_node_01",
            batteryProvider = FakeBatteryProvider(BatteryStatus(levelPercent = 80, isCharging = false)),
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            cellularClient = CellularTransportClient(rpcInvoker = rpc),
            dispatcher = TaskExecutionDispatcher(wasmEngine = engine),
            channelProvider = { MockChannel().also { channels.add(it) } },
            ledgerRepository = ledger
        )

        val result = workerLogic.executeSpotTask()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(engine.executedTask).isNull()
        assertThat(channels).hasSize(1)
        assertThat(channels.single().shutdownCalled.get()).isTrue()
        assertThat(rpc.lastClaimChannel).isSameInstanceAs(rpc.lastFetchChannel)
        assertThat(rpc.lastSubmitChannel).isSameInstanceAs(rpc.lastFetchChannel)
        assertThat(rpc.lastSubmitRequest?.errorCode).isEqualTo("ARTIFACT_NOT_FOUND")
        assertThat(rpc.lastSubmitRequest?.exitCode).isEqualTo(-1)
        assertThat(ledger.ledgerSnapshot.value.tasksFailedCount).isEqualTo(1)
        assertThat(ledger.ledgerSnapshot.value.recentEntries.first().errorCode).isEqualTo("ARTIFACT_NOT_FOUND")
    }
}
