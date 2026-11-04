package com.androgrid.worker.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Android dual-route network classification.
 *
 * Traces to: Section 6.1, [INV-001], [INV-003]
 */
class NetworkRouteMonitorTest {

    @Test
    fun testEvaluateRoute_wifiTransportYieldsLanConnected() {
        val route = NetworkRouteEvaluator.evaluate(
            isWifi = true,
            isCellular = false,
            isMetered = false
        )

        assertThat(route).isInstanceOf(NetworkRoute.LanConnected::class.java)
        val lan = route as NetworkRoute.LanConnected
        assertThat(lan.isMetered).isFalse()
    }

    @Test
    fun testEvaluateRoute_cellularTransportYieldsCellularWan() {
        val route = NetworkRouteEvaluator.evaluate(
            isWifi = false,
            isCellular = true,
            isMetered = true
        )

        assertThat(route).isInstanceOf(NetworkRoute.CellularWan::class.java)
        val cell = route as NetworkRoute.CellularWan
        assertThat(cell.isMetered).isTrue()
    }

    @Test
    fun testEvaluateRoute_disconnectedYieldsOffline() {
        val route = NetworkRouteEvaluator.evaluate(
            isWifi = false,
            isCellular = false,
            isMetered = false
        )

        assertThat(route).isEqualTo(NetworkRoute.Offline)
    }

    @Test
    fun testEvaluateRoute_wifiPrecedenceOverCellular() {
        // When both Wi-Fi and Cellular are active, Wi-Fi LAN takes precedence
        val route = NetworkRouteEvaluator.evaluate(
            isWifi = true,
            isCellular = true,
            isMetered = false
        )

        assertThat(route).isInstanceOf(NetworkRoute.LanConnected::class.java)
    }
}
