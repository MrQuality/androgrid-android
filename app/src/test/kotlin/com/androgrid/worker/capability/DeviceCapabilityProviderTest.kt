package com.androgrid.worker.capability

import com.androgrid.v1.ExecutionEngine
import com.androgrid.v1.NetworkTransportType
import com.androgrid.worker.contract.ContractConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for device capability reporting and sandbox engine isolation.
 *
 * Traces to: [REQ-VER-002], [INV-006], [ANTI-003]
 */
class DeviceCapabilityProviderTest {

    private class FakeRootChecker(private val rooted: Boolean) : RootChecker {
        override fun isDeviceRooted(): Boolean = rooted
    }

    private class FakeSystemInfoProvider(
        private val cores: Int = 8,
        private val ramBytes: Long = 6L * 1024L * 1024L * 1024L,
        private val osRelease: String = "14",
        private val primaryAbi: String = "arm64-v8a"
    ) : SystemInfoProvider {
        override fun availableProcessors(): Int = cores
        override fun totalMemoryBytes(): Long = ramBytes
        override fun osVersion(): String = osRelease
        override fun supportedAbi(): String = primaryAbi
    }

    @Test
    fun testUnrootedNode_strictlyExcludesRootShellEngine() {
        // Enforces [INV-006]: Unrooted devices MUST NOT be assigned root shell
        val provider = DeviceCapabilityProvider(
            rootChecker = FakeRootChecker(rooted = false),
            systemInfoProvider = FakeSystemInfoProvider()
        )

        val capabilities = provider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_WIFI_LAN,
            isMetered = false
        )

        assertThat(capabilities.isRooted).isFalse()
        assertThat(capabilities.supportedEnginesList).contains(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
        assertThat(capabilities.supportedEnginesList).contains(ExecutionEngine.EXECUTION_ENGINE_TOYBOX_USER)
        assertThat(capabilities.supportedEnginesList).doesNotContain(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
    }

    @Test
    fun testRootedNode_includesRootShellEngine() {
        // Enforces [INV-006]: Rooted devices report root shell capability
        val provider = DeviceCapabilityProvider(
            rootChecker = FakeRootChecker(rooted = true),
            systemInfoProvider = FakeSystemInfoProvider()
        )

        val capabilities = provider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_WIFI_LAN,
            isMetered = false
        )

        assertThat(capabilities.isRooted).isTrue()
        assertThat(capabilities.supportedEnginesList).contains(ExecutionEngine.EXECUTION_ENGINE_ROOT_SHELL)
        assertThat(capabilities.supportedEnginesList).contains(ExecutionEngine.EXECUTION_ENGINE_WASM_WASI)
    }

    @Test
    fun testDynamicCapabilityReporting_matchesSystemProperties() {
        // Enforces [REQ-VER-002]: os_version matching Build.VERSION.RELEASE and abi matching Build.SUPPORTED_ABIS[0]
        val expectedOs = "14"
        val expectedAbi = "arm64-v8a"
        val expectedCores = 8
        val expectedRam = 6L * 1024L * 1024L * 1024L

        val provider = DeviceCapabilityProvider(
            rootChecker = FakeRootChecker(rooted = false),
            systemInfoProvider = FakeSystemInfoProvider(
                cores = expectedCores,
                ramBytes = expectedRam,
                osRelease = expectedOs,
                primaryAbi = expectedAbi
            )
        )

        val capabilities = provider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN,
            isMetered = true
        )

        assertThat(capabilities.osVersion).isEqualTo(expectedOs)
        assertThat(capabilities.abi).isEqualTo(expectedAbi)
        assertThat(capabilities.cpuCores).isEqualTo(expectedCores)
        assertThat(capabilities.totalRamBytes).isEqualTo(expectedRam)
        assertThat(capabilities.isMetered).isTrue()
        assertThat(capabilities.currentTransport).isEqualTo(NetworkTransportType.NETWORK_TRANSPORT_CELLULAR_WAN)
        assertThat(capabilities.maxCellularTaskDurationSeconds)
            .isEqualTo(ContractConstants.MAX_CELLULAR_TASK_DURATION_SECONDS)
    }

    @Test
    fun testProcessorCountFallback_neverReportsZero() {
        // Boundary defense: CPU cores must be at least 1
        val provider = DeviceCapabilityProvider(
            rootChecker = FakeRootChecker(rooted = false),
            systemInfoProvider = FakeSystemInfoProvider(cores = 0)
        )

        val capabilities = provider.buildCapabilities(
            currentTransport = NetworkTransportType.NETWORK_TRANSPORT_WIFI_LAN,
            isMetered = false
        )

        assertThat(capabilities.cpuCores).isEqualTo(1)
    }
}
