package com.androgrid.worker.ui

import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.capability.BatteryStatus
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.RootChecker
import com.androgrid.worker.capability.SystemInfoProvider
import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.service.ServiceController
import com.androgrid.worker.service.WorkScheduler
import com.androgrid.worker.service.WorkerLifecycleCoordinator
import com.androgrid.worker.service.WorkerParticipationController
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.InMemoryTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerEntry
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for Worker Dashboard ViewModel state and lifecycle actions.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeRootChecker(private val rooted: Boolean = false) : RootChecker {
        override fun isDeviceRooted(): Boolean = rooted
    }

    private class FakeSystemInfo : SystemInfoProvider {
        override fun availableProcessors(): Int = 8
        override fun totalMemoryBytes(): Long = 8L * 1024L * 1024L * 1024L
        override fun osVersion(): String = "14"
        override fun supportedAbi(): String = "arm64-v8a"
    }

    private class FakeBatteryProvider(
        var level: Int = 85,
        var charging: Boolean = false,
        var temp: Int = 0
    ) : BatteryStatusProvider {
        override fun getBatteryStatus(): BatteryStatus = BatteryStatus(level, charging, temp)
    }

    private class FakeThermalListener(
        override var currentThermalStatus: Int = 0
    ) : DeviceThermalStatusListener {
        val flow = MutableStateFlow(currentThermalStatus)
        override val thermalStatusFlow: StateFlow<Int> = flow
        override fun startListening() {}
        override fun stopListening() {}
        fun updateStatus(status: Int) {
            currentThermalStatus = status
            flow.value = status
        }
    }

    private class RecordingServiceController : ServiceController {
        val started = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        override fun startWifiSyncService() { started.set(true); stopped.set(false) }
        override fun stopWifiSyncService() { stopped.set(true); started.set(false) }
    }

    private class RecordingWorkScheduler : WorkScheduler {
        val scheduled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        override fun scheduleCellularSpotWork() { scheduled.set(true); cancelled.set(false) }
        override fun cancelCellularSpotWork() { cancelled.set(true); scheduled.set(false) }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState_reflectsHardwareAndOffline() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val batteryProvider = FakeBatteryProvider()
        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = routeFlow,
            serviceController = RecordingServiceController(),
            workScheduler = RecordingWorkScheduler()
        )

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = capabilityProvider,
            batteryProvider = batteryProvider,
            coordinator = coordinator
        )

        val state = viewModel.uiState.value
        assertThat(state.nodeId).isEqualTo("node_test_pixel")
        assertThat(state.cpuCores).isEqualTo(8)
        assertThat(state.osVersion).isEqualTo("14")
        assertThat(state.abi).isEqualTo("arm64-v8a")
        assertThat(state.isRooted).isFalse()
        assertThat(state.route).isEqualTo(NetworkRoute.Offline)
        assertThat(state.statusBannerText).contains("Offline")
    }

    @Test
    fun testRouteTransition_updatesBannerTextAndColor() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo())
        val batteryProvider = FakeBatteryProvider()
        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = routeFlow,
            serviceController = RecordingServiceController(),
            workScheduler = RecordingWorkScheduler()
        )

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = capabilityProvider,
            batteryProvider = batteryProvider,
            coordinator = coordinator
        )

        // Switch to LAN Connected
        routeFlow.value = NetworkRoute.LanConnected(isMetered = false)
        testScheduler.advanceUntilIdle()

        val lanState = viewModel.uiState.value
        assertThat(lanState.route).isInstanceOf(NetworkRoute.LanConnected::class.java)
        assertThat(lanState.statusBannerText).contains("LAN Connected")

        // Switch to Cellular WAN
        routeFlow.value = NetworkRoute.CellularWan(isMetered = true)
        testScheduler.advanceUntilIdle()

        val cellState = viewModel.uiState.value
        assertThat(cellState.route).isInstanceOf(NetworkRoute.CellularWan::class.java)
        assertThat(cellState.statusBannerText).contains("Cellular WAN")
    }

    @Test
    fun testParticipationControls_togglesActiveState() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.LanConnected(isMetered = false))
        val serviceController = RecordingServiceController()
        val workScheduler = RecordingWorkScheduler()
        val coordinator = WorkerLifecycleCoordinator(routeFlow, serviceController, workScheduler)

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = coordinator
        )

        // Initially inactive
        assertThat(viewModel.uiState.value.isComputeActive).isFalse()

        // Start participation
        viewModel.startParticipation()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isComputeActive).isTrue()
        assertThat(serviceController.started.get()).isTrue()

        // Stop participation
        viewModel.stopParticipation()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isComputeActive).isFalse()
        assertThat(serviceController.stopped.get()).isTrue()
    }

    @Test
    fun testTaskCounterIncrement_updatesCompletedCount() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val ledger = InMemoryTaskLedgerRepository()
        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = WorkerLifecycleCoordinator(routeFlow, RecordingServiceController(), RecordingWorkScheduler()),
            ledgerRepository = ledger
        )

        assertThat(viewModel.uiState.value.tasksCompletedCount).isEqualTo(0)

        ledger.recordTaskExecution(
            TaskLedgerEntry("t1", 1, "WIFI_LAN", TaskLedgerStatus.COMPLETED, 0, "", 10L, 100L)
        )
        ledger.recordTaskExecution(
            TaskLedgerEntry("t2", 1, "CELLULAR_WAN", TaskLedgerStatus.COMPLETED, 0, "", 15L, 200L)
        )
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.tasksCompletedCount).isEqualTo(2)
    }

    @Test
    fun testThermalTelemetry_initialStateAndFormatting() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val thermalListener = FakeThermalListener(currentThermalStatus = 0)
        val batteryProvider = FakeBatteryProvider(level = 80, charging = true, temp = 34)

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = batteryProvider,
            coordinator = WorkerLifecycleCoordinator(routeFlow, RecordingServiceController(), RecordingWorkScheduler()),
            thermalStatusListener = thermalListener
        )

        val state = viewModel.uiState.value
        assertThat(state.batteryTemperatureCelsius).isEqualTo(34)
        assertThat(state.thermalStatus).isEqualTo(0)
        assertThat(state.batteryFormatted()).contains("34 C")
        assertThat(state.batteryFormatted()).contains("(Charging)")
        assertThat(state.thermalStatusFormatted()).isEqualTo("Nominal")
        assertThat(state.thermalBadgeColorHex()).isEqualTo("#2E7D32") // Green (< 35 C)

        // Test warning/yellow badge (35-38 C)
        val warningState = state.copy(batteryTemperatureCelsius = 36)
        assertThat(warningState.thermalBadgeColorHex()).isEqualTo("#F57F17") // Yellow

        // Test critical/red badge (> 38 C)
        val hotState = state.copy(batteryTemperatureCelsius = 40)
        assertThat(hotState.thermalBadgeColorHex()).isEqualTo("#C62828") // Red

        // Test severe thermal status red badge
        val severeState = state.copy(batteryTemperatureCelsius = 30, thermalStatus = 3)
        assertThat(severeState.thermalBadgeColorHex()).isEqualTo("#C62828") // Red
        assertThat(severeState.thermalStatusFormatted()).isEqualTo("Severe Throttling")
    }

    @Test
    fun testThermalStatusTransition_updatesUiState() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val thermalListener = FakeThermalListener(currentThermalStatus = 0)
        val batteryProvider = FakeBatteryProvider(level = 90, charging = false, temp = 33)

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = batteryProvider,
            coordinator = WorkerLifecycleCoordinator(routeFlow, RecordingServiceController(), RecordingWorkScheduler()),
            thermalStatusListener = thermalListener
        )

        assertThat(viewModel.uiState.value.thermalStatus).isEqualTo(0)

        // Transition thermal status to 3 (SEVERE)
        thermalListener.updateStatus(3)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.thermalStatus).isEqualTo(3)
        assertThat(viewModel.uiState.value.thermalBadgeColorHex()).isEqualTo("#C62828")
    }

    @Test
    fun testLedgerSnapshotUpdates_reflectCompletedFailedAndRecentEntriesWithoutClobberingRoute() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val thermalListener = FakeThermalListener(currentThermalStatus = 0)
        val ledger = InMemoryTaskLedgerRepository()

        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = WorkerLifecycleCoordinator(routeFlow, RecordingServiceController(), RecordingWorkScheduler()),
            thermalStatusListener = thermalListener,
            ledgerRepository = ledger
        )

        routeFlow.value = NetworkRoute.LanConnected(isMetered = false)
        ledger.recordTaskExecution(
            TaskLedgerEntry(
                taskId = "task-vm-1",
                runAttempt = 1,
                route = "WIFI_LAN",
                status = TaskLedgerStatus.COMPLETED,
                exitCode = 0,
                errorCode = "",
                executionDurationMs = 25L,
                completedAtEpochMs = 1000L
            )
        )
        ledger.recordTaskExecution(
            TaskLedgerEntry(
                taskId = "task-vm-2",
                runAttempt = 1,
                route = "CELLULAR_WAN",
                status = TaskLedgerStatus.FAILED,
                exitCode = 1,
                errorCode = "ERR_TRAP",
                executionDurationMs = 10L,
                completedAtEpochMs = 2000L
            )
        )
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.route).isEqualTo(NetworkRoute.LanConnected(isMetered = false))
        assertThat(state.tasksCompletedCount).isEqualTo(1)
        assertThat(state.tasksFailedCount).isEqualTo(1)
        assertThat(state.recentEntries).hasSize(2)
        assertThat(state.recentEntries.first().taskId).isEqualTo("task-vm-2")
    }

    @Test
    fun testParticipationControllerDelegation_sharesActiveStateAcrossViewModels() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.LanConnected(isMetered = false))
        val serviceController = RecordingServiceController()
        val workScheduler = RecordingWorkScheduler()
        val coordinator = WorkerLifecycleCoordinator(routeFlow, serviceController, workScheduler)
        val controller = object : WorkerParticipationController {
            private val _active = MutableStateFlow(false)
            override val isComputeActive: StateFlow<Boolean> = _active
            override fun startParticipation() {
                _active.value = true
                serviceController.startWifiSyncService()
            }
            override fun stopParticipation() {
                _active.value = false
                serviceController.stopWifiSyncService()
            }
            override fun syncActiveFromServiceStart() {}
            override fun onUiStarted() {}
            override fun onUiStopped() {}
        }

        val vm1 = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = coordinator,
            participationController = controller
        )

        vm1.startParticipation()
        testScheduler.advanceUntilIdle()
        assertThat(vm1.uiState.value.isComputeActive).isTrue()
        assertThat(serviceController.started.get()).isTrue()

        // Second ViewModel (simulating Activity recreation) observes the same active state
        val vm2 = WorkerDashboardViewModel(
            nodeId = "node_test_pixel",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = coordinator,
            participationController = controller
        )
        assertThat(vm2.uiState.value.isComputeActive).isTrue()

        vm2.stopParticipation()
        testScheduler.advanceUntilIdle()
        assertThat(vm1.uiState.value.isComputeActive).isFalse()
        assertThat(vm2.uiState.value.isComputeActive).isFalse()
    }
}
