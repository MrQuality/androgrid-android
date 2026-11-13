package com.androgrid.worker.service

import android.content.Context
import android.content.SharedPreferences
import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.network.NetworkRouteMonitor
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/**
 * Interface governing application-wide compute participation state and demand-gated monitors.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [NFR-POW-001], ADR-008
 */
interface WorkerParticipationController {
    /**
     * Authoritative stream indicating whether edge compute participation is enabled.
     */
    val isComputeActive: StateFlow<Boolean>

    /**
     * Enables compute participation, starts route coordination, and persists active state.
     */
    fun startParticipation()

    /**
     * Pauses compute participation, stops route coordination, and persists inactive state.
     */
    fun stopParticipation()

    /**
     * Reconciles participation state when WorkerForegroundService starts or sticky-restarts.
     */
    fun syncActiveFromServiceStart()

    /**
     * Notifies controller that a dashboard UI consumer entered STARTED state.
     */
    fun onUiStarted()

    /**
     * Notifies controller that a dashboard UI consumer left STARTED state.
     */
    fun onUiStopped()
}

/**
 * Application-scoped [WorkerParticipationController] coordinating [WorkerLifecycleCoordinator],
 * demand-gated network/thermal monitors, and SharedPreferences persistence off the Main Thread.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [NFR-POW-001], ADR-008
 */
class DefaultWorkerParticipationController(
    context: Context,
    private val networkMonitor: NetworkRouteMonitor,
    private val thermalListener: DeviceThermalStatusListener,
    private val coordinator: WorkerLifecycleCoordinator,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WorkerParticipationController {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _isComputeActive = MutableStateFlow(false)
    override val isComputeActive: StateFlow<Boolean> = _isComputeActive.asStateFlow()

    private val stateLock = Any()
    private val prefsMutex = Mutex()
    private var explicitToggleIssued = false
    private var activeUiCount = 0

    init {
        appScope.launch(ioDispatcher) {
            val persistedActive = prefsMutex.withLock {
                prefs.getBoolean(KEY_IS_COMPUTE_ACTIVE, false)
            }
            if (persistedActive) {
                val appDispatcher = appScope.coroutineContext[ContinuationInterceptor]
                    ?: Dispatchers.Main.immediate
                withContext(appDispatcher) {
                    val shouldRestore = synchronized(stateLock) {
                        !explicitToggleIssued && !_isComputeActive.value
                    }
                    if (shouldRestore) {
                        activateInternal(persist = false)
                    }
                }
            }
        }
    }

    override fun startParticipation() {
        synchronized(stateLock) {
            explicitToggleIssued = true
        }
        activateInternal(persist = true)
    }

    override fun stopParticipation() {
        synchronized(stateLock) {
            explicitToggleIssued = true
            if (!_isComputeActive.value) {
                updateMonitorGatingLocked()
                return
            }
            _isComputeActive.value = false
            coordinator.stop()
            coordinator.handleRouteTransition(NetworkRoute.Offline)
            updateMonitorGatingLocked()
        }
        persistActiveFlag()
    }

    override fun syncActiveFromServiceStart() {
        val needsActivation = synchronized(stateLock) {
            explicitToggleIssued = true
            !_isComputeActive.value
        }
        if (needsActivation) {
            activateInternal(persist = true)
        }
    }

    override fun onUiStarted() {
        synchronized(stateLock) {
            activeUiCount += 1
            updateMonitorGatingLocked()
        }
    }

    override fun onUiStopped() {
        synchronized(stateLock) {
            activeUiCount = (activeUiCount - 1).coerceAtLeast(0)
            updateMonitorGatingLocked()
        }
    }

    private fun activateInternal(persist: Boolean) {
        synchronized(stateLock) {
            if (_isComputeActive.value) {
                updateMonitorGatingLocked()
                return
            }
            _isComputeActive.value = true
            updateMonitorGatingLocked()
            coordinator.start(appScope)
            coordinator.handleRouteTransition(networkMonitor.currentRoute.value)
        }
        if (persist) {
            persistActiveFlag()
        }
    }

    private fun updateMonitorGatingLocked() {
        val shouldMonitor = _isComputeActive.value || activeUiCount > 0
        if (shouldMonitor) {
            networkMonitor.startMonitoring()
            thermalListener.startListening()
        } else {
            networkMonitor.stopMonitoring()
            thermalListener.stopListening()
        }
    }

    private fun persistActiveFlag() {
        appScope.launch(ioDispatcher) {
            prefsMutex.withLock {
                val authoritativeActive = synchronized(stateLock) { _isComputeActive.value }
                prefs.edit().putBoolean(KEY_IS_COMPUTE_ACTIVE, authoritativeActive).commit()
            }
        }
    }

    companion object {
        internal const val PREFS_NAME = "androgrid_participation_state"
        internal const val KEY_IS_COMPUTE_ACTIVE = "is_compute_active"
    }
}
