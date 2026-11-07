package com.androgrid.worker.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for ThermalSafetyGuard verifying temperature and thermal throttling thresholds.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003]
 */
class ThermalSafetyGuardTest {

    @Test
    fun testNominalConditions_notThrottled() {
        val guard = ThermalSafetyGuard()

        // 25 degrees Celsius, thermal status 0 (NONE)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 25, thermalStatus = 0)).isFalse()

        // 35 degrees Celsius, thermal status 1 (LIGHT)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 35, thermalStatus = 1)).isFalse()

        // 38 degrees Celsius, thermal status 2 (MODERATE)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 38, thermalStatus = 2)).isFalse()
    }

    @Test
    fun testElevatedBatteryTemperature_triggersThrottling() {
        val guard = ThermalSafetyGuard()

        // 39 degrees Celsius (> 38), thermal status 0 -> throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 39, thermalStatus = 0)).isTrue()

        // 42 degrees Celsius, thermal status 1 -> throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 42, thermalStatus = 1)).isTrue()
    }

    @Test
    fun testElevatedThermalStatus_triggersThrottling() {
        val guard = ThermalSafetyGuard()

        // 25 degrees Celsius, thermal status 3 (SEVERE) -> throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 25, thermalStatus = 3)).isTrue()

        // 30 degrees Celsius, thermal status 4 (CRITICAL) -> throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 4)).isTrue()
    }

    @Test
    fun testUncalibratedOrZeroTemperature_doesNotThrottleUnlessStatusSevere() {
        val guard = ThermalSafetyGuard()

        // Unreported or uncalibrated (<= 0)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 0, thermalStatus = 0)).isFalse()
        assertThat(guard.isThermalThrottled(temperatureCelsius = -5, thermalStatus = 2)).isFalse()

        // But severe status still throttles even if temp is <= 0
        assertThat(guard.isThermalThrottled(temperatureCelsius = 0, thermalStatus = 3)).isTrue()
    }

    @Test
    fun testCustomThresholds() {
        val customGuard = ThermalSafetyGuard(
            maxBatteryTemperatureCelsius = 40,
            maxThermalStatus = 4
        )

        // 39 degrees Celsius is allowed under 40 bound
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 39, thermalStatus = 3)).isFalse()

        // 41 degrees Celsius throttles
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 41, thermalStatus = 0)).isTrue()

        // Status 4 throttles
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 4)).isTrue()
    }
}
