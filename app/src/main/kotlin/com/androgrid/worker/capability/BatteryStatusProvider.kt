package com.androgrid.worker.capability

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Model representing device battery level and charging state.
 *
 * Traces to: [REQ-OP-003]
 */
data class BatteryStatus(
    val levelPercent: Int,
    val isCharging: Boolean
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
 * Standard Android battery status provider querying ACTION_BATTERY_CHANGED intent.
 *
 * Traces to: [REQ-OP-003]
 */
class AndroidBatteryStatusProvider(
    private val context: Context
) : BatteryStatusProvider {

    override fun getBatteryStatus(): BatteryStatus {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatusIntent = context.registerReceiver(null, intentFilter)

            val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val levelPercent = if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100f).toInt()
            } else {
                100 // Fallback if battery manager unavailable
            }

            BatteryStatus(levelPercent = levelPercent, isCharging = isCharging)
        } catch (_: Exception) {
            BatteryStatus(levelPercent = 100, isCharging = true)
        }
    }
}
