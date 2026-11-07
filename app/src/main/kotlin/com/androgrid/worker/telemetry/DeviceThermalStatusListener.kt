package com.androgrid.worker.telemetry

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Interface abstracting device thermal status monitoring and observability.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-VER-001], [REQ-VER-002]
 */
interface DeviceThermalStatusListener {
    /**
     * Current thermal status integer matching PowerManager.THERMAL_STATUS_*.
     * Returns 0 (THERMAL_STATUS_NONE) when unthrottled or on API 28.
     */
    val currentThermalStatus: Int

    /**
     * Observable flow emitting thermal status transitions.
     */
    val thermalStatusFlow: StateFlow<Int>

    /**
     * Starts listening to operating system thermal status transitions.
     */
    fun startListening()

    /**
     * Stops listening to operating system thermal status transitions.
     */
    fun stopListening()
}

/**
 * Operating system thermal status listener supporting API 29+ with graceful API 28 fallback.
 * Enforces Risk Mitigation 2 by isolating PowerManager.OnThermalStatusChangedListener behind API guard.
 *
 * Traces to: [INV-003], [NFR-POW-001], [REQ-VER-001], [REQ-VER-002]
 */
class AndroidDeviceThermalStatusListener(
    context: Context,
    private val executor: Executor = ContextCompat.getMainExecutor(context)
) : DeviceThermalStatusListener {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val _thermalStatusFlow = MutableStateFlow(0)
    override val thermalStatusFlow: StateFlow<Int> = _thermalStatusFlow.asStateFlow()

    private var api29Helper: Api29ThermalListenerHelper? = null

    override val currentThermalStatus: Int
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                try {
                    val status = powerManager.currentThermalStatus
                    if (_thermalStatusFlow.value != status) {
                        _thermalStatusFlow.value = status
                    }
                    status
                } catch (_: Exception) {
                    _thermalStatusFlow.value
                }
            } else {
                _thermalStatusFlow.value
            }
        }

    @Synchronized
    override fun startListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            if (api29Helper == null) {
                val helper = Api29ThermalListenerHelper(powerManager) { status ->
                    _thermalStatusFlow.value = status
                }
                _thermalStatusFlow.value = helper.getCurrentThermalStatus()
                helper.register(executor)
                api29Helper = helper
            }
        }
    }

    @Synchronized
    override fun stopListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            api29Helper?.unregister()
            api29Helper = null
        }
    }

    /**
     * Nested API 29+ helper preventing NoClassDefFoundError on Android 9 (API 28).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class Api29ThermalListenerHelper(
        private val powerManager: PowerManager,
        private val onStatusChanged: (Int) -> Unit
    ) {
        private val listener = PowerManager.OnThermalStatusChangedListener { status ->
            onStatusChanged(status)
        }

        fun register(executor: Executor) {
            try {
                powerManager.addThermalStatusListener(executor, listener)
            } catch (_: Exception) {
                // Catch "Listener failed to set" or RemoteException on emulators, test runners,
                // or vendor ROMs with incomplete thermal HAL implementations.
            }
        }

        fun unregister() {
            try {
                powerManager.removeThermalStatusListener(listener)
            } catch (_: Exception) {
                // Ignore if already unregistered
            }
        }

        fun getCurrentThermalStatus(): Int {
            return powerManager.currentThermalStatus
        }
    }
}
