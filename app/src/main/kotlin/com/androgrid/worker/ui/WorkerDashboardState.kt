package com.androgrid.worker.ui

import com.androgrid.worker.network.NetworkRoute

/**
 * UI State model for the mobile worker dashboard.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002]
 */
data class WorkerDashboardState(
    val nodeId: String,
    val route: NetworkRoute = NetworkRoute.Offline,
    val isComputeActive: Boolean = false,
    val isRooted: Boolean = false,
    val cpuCores: Int = 1,
    val totalRamBytes: Long = 0L,
    val osVersion: String = "unknown",
    val abi: String = "unknown",
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val tasksCompletedCount: Int = 0,
    val statusBannerText: String = "Offline: Execution Paused"
) {
    /**
     * Formats RAM in Gigabytes with 1 decimal place.
     */
    fun totalRamFormatted(): String {
        val gb = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "%.1f GB".format(gb)
    }

    /**
     * Returns human-readable battery description.
     */
    fun batteryFormatted(): String {
        val chargingSuffix = if (isCharging) " (Charging)" else ""
        return "$batteryPercent%$chargingSuffix"
    }
}
