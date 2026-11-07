package com.androgrid.worker.telemetry

/**
 * Thermal safety evaluation engine enforcing hardware temperature and OS thermal limits.
 * Postpones edge computing workloads when device battery temperature exceeds 38 C or OS reports thermal throttling.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-OP-003], [REQ-VER-002]
 */
class ThermalSafetyGuard(
    val maxBatteryTemperatureCelsius: Int = DEFAULT_MAX_BATTERY_TEMP_CELSIUS,
    val maxThermalStatus: Int = DEFAULT_MAX_THERMAL_STATUS
) {
    companion object {
        /**
         * Upper bound of 38 degrees Celsius per battery longevity and skin temperature constraints.
         */
        const val DEFAULT_MAX_BATTERY_TEMP_CELSIUS: Int = 38

        /**
         * Severe thermal status threshold (maps to PowerManager.THERMAL_STATUS_SEVERE = 3).
         * Status integer mapping:
         * 0 = NONE, 1 = LIGHT, 2 = MODERATE, 3 = SEVERE, 4 = CRITICAL, 5 = EMERGENCY, 6 = SHUTDOWN.
         */
        const val DEFAULT_MAX_THERMAL_STATUS: Int = 3
    }

    /**
     * Evaluates whether current thermal telemetry exceeds safe execution bounds.
     * Temperatures <= 0 are treated as uncalibrated or unreported and do not trigger temperature throttling.
     *
     * @param temperatureCelsius current battery temperature in Celsius.
     * @param thermalStatus current OS PowerManager thermal status.
     * @return true if compute execution must be postponed, false otherwise.
     */
    fun isThermalThrottled(temperatureCelsius: Int, thermalStatus: Int): Boolean {
        val tempThrottled = temperatureCelsius > maxBatteryTemperatureCelsius && temperatureCelsius > 0
        val statusThrottled = maxThermalStatus > 0 && thermalStatus >= maxThermalStatus
        return tempThrottled || statusThrottled
    }
}
