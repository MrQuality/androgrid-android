package com.androgrid.worker.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.androgrid.worker.network.NetworkRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Interface controlling Wi-Fi foreground synchronization service.
 *
 * Traces to: [REQ-OP-002], [REQ-VER-001]
 */
interface ServiceController {
    fun startWifiSyncService()
    fun stopWifiSyncService()
}

/**
 * Android implementation controlling WorkerForegroundService lifecycle via Intents.
 *
 * Traces to: [REQ-OP-002], [REQ-VER-001]
 */
class AndroidServiceController(private val context: Context) : ServiceController {
    override fun startWifiSyncService() {
        val intent = Intent(context, WorkerForegroundService::class.java).apply {
            action = WorkerForegroundService.ACTION_START_SYNC
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stopWifiSyncService() {
        val intent = Intent(context, WorkerForegroundService::class.java).apply {
            action = WorkerForegroundService.ACTION_STOP_SYNC
        }
        context.startService(intent)
    }
}

/**
 * Interface controlling background WorkManager cellular spot execution.
 *
 * Traces to: [REQ-OP-003], [INV-003]
 */
interface WorkScheduler {
    fun scheduleCellularSpotWork()
    fun cancelCellularSpotWork()
}

/**
 * Android WorkManager scheduler managing spot jobs.
 *
 * Traces to: [REQ-OP-003], [INV-003]
 */
class AndroidWorkScheduler(private val context: Context) : WorkScheduler {
    override fun scheduleCellularSpotWork() {
        val request = CellularSpotWorker.buildWorkRequest()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CellularSpotWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun cancelCellularSpotWork() {
        WorkManager.getInstance(context).cancelUniqueWork(CellularSpotWorker.WORK_NAME)
    }
}

/**
 * Coordinates worker node operational modes across network transport states.
 * Strictly adheres to Section 6.1 Dual-Route Connection State Matrix.
 *
 * Traces to: Section 6.1, [INV-001], [INV-003], [REQ-OP-001], [REQ-OP-002], [REQ-OP-003]
 */
class WorkerLifecycleCoordinator(
    private val routeFlow: StateFlow<NetworkRoute>,
    private val serviceController: ServiceController,
    private val workScheduler: WorkScheduler
) {
    private var observationJob: Job? = null

    /**
     * Starts observing network route changes and triggers corresponding transitions.
     */
    fun start(scope: CoroutineScope) {
        observationJob = scope.launch {
            routeFlow.collect { route ->
                handleRouteTransition(route)
            }
        }
    }

    /**
     * Stops observing route transitions.
     */
    fun stop() {
        observationJob?.cancel()
        observationJob = null
    }

    /**
     * Maps route state to corresponding service lifecycle per Section 6.1:
     * - LAN Connected: Start Wi-Fi foreground service; cancel cellular spot work.
     * - Cellular WAN: Stop Wi-Fi foreground service; schedule cellular spot work.
     * - Offline: Stop Wi-Fi foreground service; cancel cellular spot work.
     */
    fun handleRouteTransition(route: NetworkRoute) {
        when (route) {
            is NetworkRoute.LanConnected -> {
                workScheduler.cancelCellularSpotWork()
                serviceController.startWifiSyncService()
            }
            is NetworkRoute.CellularWan -> {
                serviceController.stopWifiSyncService()
                workScheduler.scheduleCellularSpotWork()
            }
            is NetworkRoute.Offline -> {
                serviceController.stopWifiSyncService()
                workScheduler.cancelCellularSpotWork()
            }
        }
    }
}
