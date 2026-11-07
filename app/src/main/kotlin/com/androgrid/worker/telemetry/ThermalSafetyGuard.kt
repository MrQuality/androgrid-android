package com.androgrid.worker.telemetry

/**
 * Thermal safety evaluation engine enforcing hardware temperature and OS thermal limits.
 * Implements hysteresis dampening to prevent thermal flapping loops between cutoff and resume thresholds.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-VER-002]
 */
class ThermalSafetyGuard(
    val maxBatteryTemperatureCelsius: Int = DEFAULT_MAX_BATTERY_TEMP_CELSIUS,
    val resumeBatteryTemperatureCelsius: Int = DEFAULT_RESUME_BATTERY_TEMP_CELSIUS,
    val maxThermalStatus: Int = DEFAULT_MAX_THERMAL_STATUS,
    val resumeThermalStatus: Int = DEFAULT_RESUME_THERMAL_STATUS
) {
    companion object {
        /**
         * Upper bound cutoff of 38 degrees Celsius per battery longevity constraints.
         */
        const val DEFAULT_MAX_BATTERY_TEMP_CELSIUS: Int = 38

        /**
         * Lower bound resume threshold of 35 degrees Celsius preventing rapid thermal oscillation.
         */
        const val DEFAULT_RESUME_BATTERY_TEMP_CELSIUS: Int = 35

        /**
         * Severe thermal status cutoff threshold (maps to PowerManager.THERMAL_STATUS_SEVERE = 3).
         * Status integer mapping:
         * 0 = NONE, 1 = LIGHT, 2 = MODERATE, 3 = SEVERE, 4 = CRITICAL, 5 = EMERGENCY, 6 = SHUTDOWN.
         */
        const val DEFAULT_MAX_THERMAL_STATUS: Int = 3

        /**
         * Safe thermal status resume threshold (maps to PowerManager.THERMAL_STATUS_LIGHT = 1).
         */
        const val DEFAULT_RESUME_THERMAL_STATUS: Int = 1
    }

    private var currentlyThrottled: Boolean = false

    /**
     * Evaluates whether current thermal telemetry exceeds safe execution bounds with hysteresis dampening.
     * When unthrottled, execution halts if temperature > maxBatteryTemperatureCelsius or status >= maxThermalStatus.
     * Once throttled, execution remains suspended until temperature cools <= resumeBatteryTemperatureCelsius
     * and status <= resumeThermalStatus.
     *
     * Temperatures <= 0 are treated as uncalibrated or unreported and do not trigger temperature throttling.
     *
     * @param temperatureCelsius current battery temperature in Celsius.
     * @param thermalStatus current OS PowerManager thermal status.
     * @return true if compute execution must be postponed, false otherwise.
     */
    @Synchronized
    fun isThermalThrottled(temperatureCelsius: Int, thermalStatus: Int): Boolean {
        if (!currentlyThrottled) {
            val tempExceeded = temperatureCelsius > maxBatteryTemperatureCelsius && temperatureCelsius > 0
            val statusExceeded = maxThermalStatus > 0 && thermalStatus >= maxThermalStatus
            if (tempExceeded || statusExceeded) {
                currentlyThrottled = true
            }
        } else {
            val tempCooled = temperatureCelsius <= resumeBatteryTemperatureCelsius || temperatureCelsius <= 0
            val statusCooled = thermalStatus <= resumeThermalStatus
            if (tempCooled && statusCooled) {
                currentlyThrottled = false
            }
        }
        return currentlyThrottled
    }

    /**
     * Resets the internal throttled state back to unthrottled.
     */
    @Synchronized
    fun reset() {
        currentlyThrottled = false
    }
}
