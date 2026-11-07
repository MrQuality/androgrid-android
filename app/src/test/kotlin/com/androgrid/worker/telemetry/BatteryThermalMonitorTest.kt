package com.androgrid.worker.telemetry

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for BatteryThermalMonitor verifying sticky broadcast parsing and Celsius conversion.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-VER-002]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryThermalMonitorTest {

    private lateinit var context: Context
    private lateinit var monitor: AndroidBatteryThermalMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        monitor = AndroidBatteryThermalMonitor(context)
    }

    private fun broadcastBatteryState(
        level: Int = 80,
        scale: Int = 100,
        status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
        rawTemperatureTenths: Int = 365
    ) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
            putExtra(BatteryManager.EXTRA_STATUS, status)
            putExtra(BatteryManager.EXTRA_TEMPERATURE, rawTemperatureTenths)
        }
        context.sendStickyBroadcast(intent)
    }

    @Test
    fun testBatteryStatus_extractsLevelChargingAndCelsiusTemperature() {
        broadcastBatteryState(
            level = 85,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            rawTemperatureTenths = 372 // 37.2 degrees Celsius -> 37
        )

        val status = monitor.getBatteryStatus()

        assertThat(status.levelPercent).isEqualTo(85)
        assertThat(status.isCharging).isTrue()
        assertThat(status.temperatureCelsius).isEqualTo(37)
        assertThat(monitor.getBatteryTemperatureCelsius()).isEqualTo(37)
    }

    @Test
    fun testBatteryTemperatureConversion_roundsTenthsOfDegreeDown() {
        // 389 tenths = 38.9 degrees -> 38
        broadcastBatteryState(rawTemperatureTenths = 389)
        assertThat(monitor.getBatteryTemperatureCelsius()).isEqualTo(38)

        // 405 tenths = 40.5 degrees -> 40
        broadcastBatteryState(rawTemperatureTenths = 405)
        assertThat(monitor.getBatteryTemperatureCelsius()).isEqualTo(40)
    }

    @Test
    fun testUncalibratedOrMissingTemperature_reportsZero() {
        // Negative temperature in intent
        broadcastBatteryState(rawTemperatureTenths = -1)
        assertThat(monitor.getBatteryTemperatureCelsius()).isEqualTo(0)

        // Zero temperature in intent
        broadcastBatteryState(rawTemperatureTenths = 0)
        assertThat(monitor.getBatteryTemperatureCelsius()).isEqualTo(0)
    }
}
