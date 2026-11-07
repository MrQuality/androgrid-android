package com.androgrid.worker.telemetry

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for DeviceThermalStatusListener across API 28 and API 34.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-VER-001], [REQ-VER-002]
 */
@RunWith(RobolectricTestRunner::class)
class DeviceThermalStatusListenerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    @Config(sdk = [34])
    fun testApi34_initialStatusAndListeningLifecycle() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadowPower = Shadows.shadowOf(powerManager)
        shadowPower.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)

        val listener = AndroidDeviceThermalStatusListener(context)
        assertThat(listener.currentThermalStatus).isEqualTo(PowerManager.THERMAL_STATUS_NONE)

        listener.startListening()

        // Simulate thermal status change to SEVERE (3)
        shadowPower.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertThat(listener.currentThermalStatus).isEqualTo(PowerManager.THERMAL_STATUS_SEVERE)
        assertThat(listener.thermalStatusFlow.value).isEqualTo(PowerManager.THERMAL_STATUS_SEVERE)

        listener.stopListening()
    }

    @Test
    @Config(sdk = [28])
    fun testApi28_legacyFallbackReturnsZeroWithoutCrash() {
        // Enforces [REQ-VER-001] & [REQ-VER-002]: Graceful fallback on API 28 without NoClassDefFoundError
        val listener = AndroidDeviceThermalStatusListener(context)

        assertThat(listener.currentThermalStatus).isEqualTo(0)
        assertThat(listener.thermalStatusFlow.value).isEqualTo(0)

        // Starting and stopping listening must be safe no-ops
        listener.startListening()
        assertThat(listener.currentThermalStatus).isEqualTo(0)
        listener.stopListening()
    }
}
