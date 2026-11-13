package com.androgrid.worker.ui

import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.telemetry.TaskLedgerEntry

/**
 * UI State model for the mobile worker dashboard.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002], [INV-003], [NFR-POW-001], ADR-008
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
    val batteryTemperatureCelsius: Int = 0,
    val thermalStatus: Int = 0,
    val tasksCompletedCount: Int = 0,
    val tasksFailedCount: Int = 0,
    val recentEntries: List<TaskLedgerEntry> = emptyList(),
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
     * Returns human-readable battery description including temperature.
     */
    fun batteryFormatted(): String {
        val chargingSuffix = if (isCharging) " (Charging)" else ""
        val tempSuffix = if (batteryTemperatureCelsius > 0) " | ${batteryTemperatureCelsius} C" else ""
        return "$batteryPercent%$chargingSuffix$tempSuffix"
    }

    /**
     * Formats thermal status string matching PowerManager.THERMAL_STATUS_*.
     */
    fun thermalStatusFormatted(): String {
        return when (thermalStatus) {
            0 -> "Nominal"
            1 -> "Light Throttling"
            2 -> "Moderate Throttling"
            3 -> "Severe Throttling"
            4 -> "Critical Throttling"
            5 -> "Emergency Throttling"
            6 -> "Shutdown Imminent"
            else -> "Status: $thermalStatus"
        }
    }

    /**
     * Returns thermal badge color hex code:
     * Green (< 35 C), Yellow (35-38 C), Red (> 38 C or severe status >= 3).
     */
    fun thermalBadgeColorHex(): String {
        return when {
            batteryTemperatureCelsius > 38 || thermalStatus >= 3 -> "#C62828" // Red
            batteryTemperatureCelsius >= 35 || thermalStatus >= 1 -> "#F57F17" // Yellow
            else -> "#2E7D32" // Green
        }
    }
}
