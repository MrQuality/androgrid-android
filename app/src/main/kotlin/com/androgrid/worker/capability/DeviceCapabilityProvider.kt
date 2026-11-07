package com.androgrid.worker.capability

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.NetworkTransportType
import com.androgrid.v1.NodeCapabilities
import com.androgrid.worker.contract.ContractConstants

/**
 * Constructs canonical NodeCapabilities payload for cluster registration and claim requests.
 * Enforces unrooted sandbox boundary ([INV-006]) and dynamic version reporting ([REQ-VER-002]).
 *
 * Traces to: [INV-006], [REQ-VER-002], [ANTI-003]
 */
class DeviceCapabilityProvider(
    private val rootChecker: RootChecker = StandardRootChecker(),
    private val systemInfoProvider: SystemInfoProvider
) {

    /**
     * Builds immutable NodeCapabilities protobuf contract object.
     *
     * @param currentTransport active network transport type.
     * @param isMetered whether current connection is metered.
     * @param batteryTemperatureCelsius current battery temperature in Celsius (default 0).
     * @param thermalStatus current OS PowerManager thermal status (default 0).
     * @return fully validated NodeCapabilities protobuf instance.
     */
    fun buildCapabilities(
        currentTransport: NetworkTransportType,
        isMetered: Boolean,
        batteryTemperatureCelsius: Int = 0,
        thermalStatus: Int = 0
    ): NodeCapabilities {
        val isRooted = rootChecker.isDeviceRooted()
        val engines = resolveSupportedEngines(isRooted)
        val cores = systemInfoProvider.availableProcessors().coerceAtLeast(1)
        val totalRam = systemInfoProvider.totalMemoryBytes()
        val osVersion = systemInfoProvider.osVersion()
        val primaryAbi = systemInfoProvider.supportedAbi()

        return NodeCapabilities.newBuilder()
            .setIsRooted(isRooted)
            .addAllSupportedEngines(engines)
            .setCpuCores(cores)
            .setTotalRamBytes(totalRam)
            .setOsVersion(osVersion)
            .setAbi(primaryAbi)
            .setIsMetered(isMetered)
            .setCurrentTransport(currentTransport)
            .setMaxCellularTaskDurationSeconds(ContractConstants.MAX_CELLULAR_TASK_DURATION_SECONDS)
            .setBatteryTemperatureCelsius(batteryTemperatureCelsius)
            .setThermalStatus(thermalStatus)
            .build()
    }

    /**
     * Resolves assignable execution engines based on root sandbox boundary.
     * Enforces [INV-006]: Unrooted devices MUST NOT support EXECUTION_ENGINE_ROOT_SHELL.
     *
     * @param isRooted true if device has verified root access.
     * @return list of assignable execution engines.
     */
    private fun resolveSupportedEngines(isRooted: Boolean): List<ExecutionEngine> {
        val engines = mutableListOf(
            ExecutionEngine.EXECUTION_ENGINE_WASM_WASI,
            ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER
        )
        if (isRooted) {
            engines.add(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
        }
        return engines
    }
}
