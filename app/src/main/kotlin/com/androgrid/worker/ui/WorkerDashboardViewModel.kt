package com.androgrid.worker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.service.WorkerLifecycleCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing worker dashboard UI state and user participation lifecycle.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002]
 */
class WorkerDashboardViewModel(
    private val nodeId: String,
    private val routeFlow: StateFlow<NetworkRoute>,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val batteryProvider: BatteryStatusProvider,
    private val coordinator: WorkerLifecycleCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<WorkerDashboardState> = _uiState.asStateFlow()

    init {
        // Observe route transitions
        viewModelScope.launch {
            routeFlow.collect { route ->
                onRouteChanged(route)
            }
        }
    }

    private fun createInitialState(): WorkerDashboardState {
        val caps = capabilityProvider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_UNSPECIFIED,
            isMetered = false
        )
        val battery = batteryProvider.getBatteryStatus()

        return WorkerDashboardState(
            nodeId = nodeId,
            route = NetworkRoute.Offline,
            isComputeActive = false,
            isRooted = caps.isRooted,
            cpuCores = caps.cpuCores,
            totalRamBytes = caps.totalRamBytes,
            osVersion = caps.osVersion,
            abi = caps.abi,
            batteryPercent = battery.levelPercent,
            isCharging = battery.isCharging,
            tasksCompletedCount = 0,
            statusBannerText = "Offline: Execution Paused"
        )
    }

    private fun onRouteChanged(route: NetworkRoute) {
        val bannerText = when (route) {
            is NetworkRoute.LanConnected -> "LAN Connected: Wi-Fi Direct Sync Active"
            is NetworkRoute.CellularWan -> "Cellular WAN: Disconnected Spot Execution"
            is NetworkRoute.Offline -> "Offline: Execution Paused"
        }

        val battery = batteryProvider.getBatteryStatus()

        _uiState.value = _uiState.value.copy(
            route = route,
            statusBannerText = bannerText,
            batteryPercent = battery.levelPercent,
            isCharging = battery.isCharging
        )

        // If compute participation is enabled, propagate to coordinator
        if (_uiState.value.isComputeActive) {
            coordinator.handleRouteTransition(route)
        }
    }

    /**
     * User initiates edge compute participation.
     */
    fun startParticipation() {
        _uiState.value = _uiState.value.copy(isComputeActive = true)
        coordinator.start(viewModelScope)
        coordinator.handleRouteTransition(_uiState.value.route)
    }

    /**
     * User pauses edge compute participation.
     */
    fun stopParticipation() {
        _uiState.value = _uiState.value.copy(isComputeActive = false)
        coordinator.stop()
        coordinator.handleRouteTransition(NetworkRoute.Offline)
    }

    /**
     * Increments the cumulative completed tasks counter.
     */
    fun incrementCompletedTasks() {
        val currentCount = _uiState.value.tasksCompletedCount
        _uiState.value = _uiState.value.copy(tasksCompletedCount = currentCount + 1)
    }
}
