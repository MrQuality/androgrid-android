package com.androgrid.worker.capability

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Model representing device battery level, charging state, and temperature.
 *
 * Traces to: [REQ-OP-003], [NFR-POW-001]
 */
data class BatteryStatus(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Int = 0
)

/**
 * Interface abstracting battery status retrieval for testability.
 *
 * Traces to: [REQ-OP-003]
 */
interface BatteryStatusProvider {
    /**
     * Reports current battery status.
     *
     * @return BatteryStatus containing level percentage (0-100) and charging indicator.
     */
    fun getBatteryStatus(): BatteryStatus
}

/**
 * Standard Android battery status provider delegating to AndroidBatteryThermalMonitor.
 *
 * Traces to: [REQ-OP-003], [NFR-POW-001]
 */
class AndroidBatteryStatusProvider(
    private val context: Context,
    private val monitor: BatteryStatusProvider = com.androgrid.worker.telemetry.AndroidBatteryThermalMonitor(context)
) : BatteryStatusProvider {

    override fun getBatteryStatus(): BatteryStatus {
        return monitor.getBatteryStatus()
    }
}
