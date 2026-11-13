package com.androgrid.worker

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import com.androgrid.worker.network.ClusterSettingsRepository
import com.androgrid.worker.network.NetworkRouteMonitor
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.service.AndroidServiceController
import com.androgrid.worker.service.AndroidWorkScheduler
import com.androgrid.worker.service.DefaultWorkerParticipationController
import com.androgrid.worker.service.WorkerLifecycleCoordinator
import com.androgrid.worker.service.WorkerParticipationController
import com.androgrid.worker.telemetry.AndroidDeviceThermalStatusListener
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.SharedPreferencesTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped dependency container for AndroGrid Worker.
 * Performs zero disk I/O and starts zero listeners in [onCreate] so cold starts
 * and Robolectric tests remain fast and StrictMode-clean.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [NFR-POW-001], ADR-008
 */
class AndroGridApplication : Application() {

    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val settingsRepository: ClusterSettingsRepository by lazy {
        SharedPreferencesClusterSettingsRepository.getInstance(this)
    }

    val taskLedgerRepository: TaskLedgerRepository by lazy {
        SharedPreferencesTaskLedgerRepository.getInstance(this)
    }

    val networkRouteMonitor: NetworkRouteMonitor by lazy {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        NetworkRouteMonitor(connectivityManager)
    }

    val thermalStatusListener: DeviceThermalStatusListener by lazy {
        AndroidDeviceThermalStatusListener(this)
    }

    val lifecycleCoordinator: WorkerLifecycleCoordinator by lazy {
        WorkerLifecycleCoordinator(
            routeFlow = networkRouteMonitor.currentRoute,
            serviceController = AndroidServiceController(this),
            workScheduler = AndroidWorkScheduler(this)
        )
    }

    val participationController: WorkerParticipationController by lazy {
        DefaultWorkerParticipationController(
            context = this,
            networkMonitor = networkRouteMonitor,
            thermalListener = thermalStatusListener,
            coordinator = lifecycleCoordinator,
            appScope = appScope
        )
    }
}
