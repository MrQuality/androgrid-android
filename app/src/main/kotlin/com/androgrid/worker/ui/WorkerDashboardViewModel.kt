package com.androgrid.worker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.capability.BatteryStatusProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.service.WorkerLifecycleCoordinator
import com.androgrid.worker.service.WorkerParticipationController
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.InMemoryTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing worker dashboard UI state and user participation lifecycle.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002], [INV-003], [NFR-POW-001], ADR-008
 */
class WorkerDashboardViewModel(
    private val nodeId: String,
    private val routeFlow: StateFlow<NetworkRoute>,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val batteryProvider: BatteryStatusProvider,
    private val coordinator: WorkerLifecycleCoordinator,
    private val thermalStatusListener: DeviceThermalStatusListener? = null,
    private val ledgerRepository: TaskLedgerRepository = InMemoryTaskLedgerRepository(),
    private val participationController: WorkerParticipationController? = null
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

        // Observe thermal status transitions
        viewModelScope.launch {
            thermalStatusListener?.thermalStatusFlow?.collect { thermalStatus ->
                val battery = batteryProvider.getBatteryStatus()
                _uiState.update { state ->
                    state.copy(
                        batteryPercent = battery.levelPercent,
                        isCharging = battery.isCharging,
                        batteryTemperatureCelsius = battery.temperatureCelsius,
                        thermalStatus = thermalStatus
                    )
                }
            }
        }

        // Observe durable task execution ledger updates
        viewModelScope.launch {
            ledgerRepository.ledgerSnapshot.collect { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        tasksCompletedCount = snapshot.tasksCompletedCount,
                        tasksFailedCount = snapshot.tasksFailedCount,
                        recentEntries = snapshot.recentEntries
                    )
                }
            }
        }

        // Observe Application-scoped compute participation state
        viewModelScope.launch {
            participationController?.isComputeActive?.collect { active ->
                _uiState.update { state ->
                    state.copy(isComputeActive = active)
                }
            }
        }
    }

    private fun createInitialState(): WorkerDashboardState {
        val caps = capabilityProvider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_UNSPECIFIED,
            isMetered = false
        )
        val battery = batteryProvider.getBatteryStatus()
        val thermalStatus = thermalStatusListener?.currentThermalStatus ?: 0
        val ledgerSnapshot = ledgerRepository.ledgerSnapshot.value

        return WorkerDashboardState(
            nodeId = nodeId,
            route = NetworkRoute.Offline,
            isComputeActive = participationController?.isComputeActive?.value ?: false,
            isRooted = caps.isRooted,
            cpuCores = caps.cpuCores,
            totalRamBytes = caps.totalRamBytes,
            osVersion = caps.osVersion,
            abi = caps.abi,
            batteryPercent = battery.levelPercent,
            isCharging = battery.isCharging,
            batteryTemperatureCelsius = battery.temperatureCelsius,
            thermalStatus = thermalStatus,
            tasksCompletedCount = ledgerSnapshot.tasksCompletedCount,
            tasksFailedCount = ledgerSnapshot.tasksFailedCount,
            recentEntries = ledgerSnapshot.recentEntries,
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
        val thermalStatus = thermalStatusListener?.currentThermalStatus ?: _uiState.value.thermalStatus

        _uiState.update { state ->
            state.copy(
                route = route,
                statusBannerText = bannerText,
                batteryPercent = battery.levelPercent,
                isCharging = battery.isCharging,
                batteryTemperatureCelsius = battery.temperatureCelsius,
                thermalStatus = thermalStatus
            )
        }

        // Fallback when running without Application-scoped participationController (unit tests)
        if (participationController == null && _uiState.value.isComputeActive) {
            coordinator.handleRouteTransition(route)
        }
    }

    /**
     * User initiates edge compute participation.
     */
    fun startParticipation() {
        if (participationController != null) {
            participationController.startParticipation()
        } else {
            _uiState.update { it.copy(isComputeActive = true) }
            coordinator.start(viewModelScope)
            coordinator.handleRouteTransition(_uiState.value.route)
        }
    }

    /**
     * User pauses edge compute participation.
     */
    fun stopParticipation() {
        if (participationController != null) {
            participationController.stopParticipation()
        } else {
            _uiState.update { it.copy(isComputeActive = false) }
            coordinator.stop()
            coordinator.handleRouteTransition(NetworkRoute.Offline)
        }
    }
}
