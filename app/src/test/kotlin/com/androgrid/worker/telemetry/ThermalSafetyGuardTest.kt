package com.androgrid.worker.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for ThermalSafetyGuard verifying temperature, thermal throttling thresholds,
 * and hysteresis dampening preventing thermal flapping loops.
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
            resumeBatteryTemperatureCelsius = 36,
            maxThermalStatus = 4,
            resumeThermalStatus = 2
        )

        // 39 degrees Celsius is allowed under 40 bound
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 39, thermalStatus = 3)).isFalse()

        // 41 degrees Celsius throttles
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 41, thermalStatus = 0)).isTrue()

        // 38 degrees Celsius: still throttled (since 38 > 36 resume threshold)
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 38, thermalStatus = 0)).isTrue()

        // 36 degrees Celsius: cools below resume threshold, resumes
        assertThat(customGuard.isThermalThrottled(temperatureCelsius = 36, thermalStatus = 1)).isFalse()
    }

    @Test
    fun testThermalHysteresis_preventsFlappingBetweenCutoffAndResumeThresholds() {
        val guard = ThermalSafetyGuard()

        // Initial healthy state at 36 degrees Celsius
        assertThat(guard.isThermalThrottled(temperatureCelsius = 36, thermalStatus = 0)).isFalse()

        // Temperature rises to 38 degrees Celsius: still within upper bound
        assertThat(guard.isThermalThrottled(temperatureCelsius = 38, thermalStatus = 0)).isFalse()

        // Temperature spikes to 39 degrees Celsius (> 38 C cutoff): triggers throttled state
        assertThat(guard.isThermalThrottled(temperatureCelsius = 39, thermalStatus = 0)).isTrue()

        // Flapping scenario: temperature drops back to 38 C
        // Under hysteresis dampening, device MUST remain throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 38, thermalStatus = 0)).isTrue()

        // Drops to 37 C: still throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 37, thermalStatus = 0)).isTrue()

        // Drops to 36 C: still throttled
        assertThat(guard.isThermalThrottled(temperatureCelsius = 36, thermalStatus = 0)).isTrue()

        // Cooldown reaches resume threshold of 35 C: resumes compute execution
        assertThat(guard.isThermalThrottled(temperatureCelsius = 35, thermalStatus = 0)).isFalse()

        // Normal temperature fluctuation back to 37 C: remains unthrottled until cutoff exceeded again
        assertThat(guard.isThermalThrottled(temperatureCelsius = 37, thermalStatus = 0)).isFalse()
    }

    @Test
    fun testThermalStatusHysteresis_requiresStatusCoolDownToResume() {
        val guard = ThermalSafetyGuard()

        // Nominal status 1 (LIGHT)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 1)).isFalse()

        // Severe status 3: triggers throttling
        assertThat(guard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 3)).isTrue()

        // Status drops to 2 (MODERATE): must remain throttled until <= resume threshold (1)
        assertThat(guard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 2)).isTrue()

        // Status drops to 1 (LIGHT <= resume status): resumes
        assertThat(guard.isThermalThrottled(temperatureCelsius = 30, thermalStatus = 1)).isFalse()
    }
}
