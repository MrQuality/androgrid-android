package com.androgrid.worker.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android Dual-Route Connection State representation.
 *
 * Traces to: Section 6.1, [INV-001], [INV-003]
 */
sealed class NetworkRoute {
    /**
     * Active Wi-Fi LAN connection directed to local host orchestrator.
     * Persistent declarative sync loop active.
     */
    data class LanConnected(val isMetered: Boolean) : NetworkRoute()

    /**
     * Active metered Cellular WAN connection directed to Cloudflare Anycast ingress.
     * Ephemeral zero-keepalive unary execution active.
     */
    data class CellularWan(val isMetered: Boolean) : NetworkRoute()

    /**
     * No active network transport. Execution paused.
     */
    data object Offline : NetworkRoute()
}

/**
 * Pure evaluation logic for network capabilities mapping to dual-route state.
 *
 * Traces to: Section 6.1
 */
object NetworkRouteEvaluator {
    /**
     * Evaluates route state based on transport flags.
     * Wi-Fi LAN takes precedence over Cellular WAN if both are reported.
     *
     * @param isWifi true if TRANSPORT_WIFI is present.
     * @param isCellular true if TRANSPORT_CELLULAR is present.
     * @param isMetered true if connection is metered.
     * @return evaluated NetworkRoute.
     */
    fun evaluate(isWifi: Boolean, isCellular: Boolean, isMetered: Boolean): NetworkRoute {
        return when {
            isWifi -> NetworkRoute.LanConnected(isMetered = isMetered)
            isCellular -> NetworkRoute.CellularWan(isMetered = isMetered)
            else -> NetworkRoute.Offline
        }
    }
}

/**
 * Monitors Android network interface transitions and updates connection state flow.
 * Strictly adheres to [INV-001]: Performs client callbacks only, opens no listening sockets.
 *
 * Traces to: Section 6.1, [INV-001], [INV-003]
 */
class NetworkRouteMonitor(
    private val connectivityManager: ConnectivityManager
) {
    private val _currentRoute = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
    val currentRoute: StateFlow<NetworkRoute> = _currentRoute.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            _currentRoute.value = NetworkRouteEvaluator.evaluate(
                isWifi = isWifi,
                isCellular = isCellular,
                isMetered = isMetered
            )
        }

        override fun onLost(network: Network) {
            val active = connectivityManager.activeNetwork
            if (active == null || active == network) {
                _currentRoute.value = NetworkRoute.Offline
            } else {
                val caps = connectivityManager.getNetworkCapabilities(active)
                if (caps != null) {
                    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    _currentRoute.value = NetworkRouteEvaluator.evaluate(
                        isWifi = isWifi,
                        isCellular = isCellular,
                        isMetered = isMetered
                    )
                } else {
                    _currentRoute.value = NetworkRoute.Offline
                }
            }
        }
    }

    private var isMonitoring: Boolean = false

    /**
     * Registers network callback with Android ConnectivityManager idempotently.
     */
    @Synchronized
    fun startMonitoring() {
        if (isMonitoring) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        isMonitoring = true
    }

    /**
     * Unregisters network callback to prevent leaks idempotently.
     */
    @Synchronized
    fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Ignore if already unregistered
        } finally {
            isMonitoring = false
        }
    }
}
