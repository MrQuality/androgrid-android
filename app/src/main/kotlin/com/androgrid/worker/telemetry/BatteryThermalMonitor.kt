package com.androgrid.worker.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.androgrid.worker.capability.BatteryStatus
import com.androgrid.worker.capability.BatteryStatusProvider

/**
 * Interface abstracting hardware battery temperature and telemetry retrieval.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-VER-002]
 */
interface BatteryThermalMonitor : BatteryStatusProvider {
    /**
     * Reports current battery temperature in integer degrees Celsius.
     * Converted from tenths of a degree in BatteryManager.EXTRA_TEMPERATURE.
     * Returns 0 if uncalibrated or unavailable.
     *
     * @return current battery temperature in degrees Celsius.
     */
    fun getBatteryTemperatureCelsius(): Int
}

/**
 * Production implementation querying sticky ACTION_BATTERY_CHANGED broadcast intent passively.
 * Enforces Risk Mitigation 1 by using passive sticky intent resolution rather than continuous listeners.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-VER-002]
 */
class AndroidBatteryThermalMonitor(
    private val context: Context
) : BatteryThermalMonitor {

    override fun getBatteryTemperatureCelsius(): Int {
        return getBatteryStatus().temperatureCelsius
    }

    override fun getBatteryStatus(): BatteryStatus {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            // Query sticky intent passively without registering a continuous active receiver
            val batteryStatusIntent = context.registerReceiver(null, intentFilter)

            val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val rawTempTenths = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val levelPercent = if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100f).toInt()
            } else {
                100 // Fallback if battery manager is unavailable
            }

            // Convert integer tenths of a degree Celsius (e.g., 365 -> 36 C)
            val temperatureCelsius = if (rawTempTenths > 0) {
                rawTempTenths / 10
            } else {
                0 // Unreported or uncalibrated hardware sensor
            }

            BatteryStatus(
                levelPercent = levelPercent,
                isCharging = isCharging,
                temperatureCelsius = temperatureCelsius
            )
        } catch (_: Exception) {
            BatteryStatus(levelPercent = 100, isCharging = true, temperatureCelsius = 0)
        }
    }
}
