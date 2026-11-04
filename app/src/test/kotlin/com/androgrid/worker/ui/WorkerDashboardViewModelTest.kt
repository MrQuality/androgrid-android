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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakeBatteryProvider(var level: Int = 85, var charging: Boolean = false) : BatteryStatusProvider {
        override fun getBatteryStatus(): BatteryStatus = BatteryStatus(level, charging)
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
        val viewModel = WorkerDashboardViewModel(
            nodeId = "node_test",
            routeFlow = routeFlow,
            capabilityProvider = DeviceCapabilityProvider(FakeRootChecker(), FakeSystemInfo()),
            batteryProvider = FakeBatteryProvider(),
            coordinator = WorkerLifecycleCoordinator(routeFlow, RecordingServiceController(), RecordingWorkScheduler())
        )

        assertThat(viewModel.uiState.value.tasksCompletedCount).isEqualTo(0)

        viewModel.incrementCompletedTasks()
        viewModel.incrementCompletedTasks()

        assertThat(viewModel.uiState.value.tasksCompletedCount).isEqualTo(2)
    }
}
