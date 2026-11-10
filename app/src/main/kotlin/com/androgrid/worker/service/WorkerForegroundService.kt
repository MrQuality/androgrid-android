package com.androgrid.worker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.androgrid.worker.R
import com.androgrid.v1.NetworkTransportType
import com.androgrid.v1.RegisterNodeRequest
import com.androgrid.v1.SyncNodeStateRequest
import com.androgrid.v1.TaskExecutionReport
import com.androgrid.v1.TaskState
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.NodeIdProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.network.WifiSyncClient
import com.androgrid.worker.telemetry.AndroidDeviceThermalStatusListener
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.ThermalSafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android Foreground Service executing Wi-Fi state synchronization.
 * Bifurcates lifecycle between API 34+ typed foreground service and legacy fallbacks.
 * Executes declarative synchronization loop every 2.0 seconds ([REQ-OP-002]).
 *
 * Traces to: [REQ-OP-001], [REQ-OP-002], [REQ-VER-001], [INV-001], [INV-002], [NFR-SEC-001]
 */
class WorkerForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID: Int = 1001
        const val CHANNEL_ID: String = "androgrid_worker_sync_channel"

        const val ACTION_START_SYNC: String = "com.androgrid.worker.action.START_SYNC"
        const val ACTION_STOP_SYNC: String = "com.androgrid.worker.action.STOP_SYNC"

        /**
         * Declarative Wi-Fi synchronization interval in milliseconds (2.0s).
         * Traces to: [REQ-OP-002]
         */
        const val SYNC_INTERVAL_MS: Long = 2000L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var syncJob: Job? = null

    private var wifiClient: WifiSyncClient? = null
    private val completedReports = ConcurrentLinkedQueue<TaskExecutionReport>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SYNC

        when (action) {
            ACTION_STOP_SYNC -> {
                stopSyncLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SYNC -> {
                startForegroundWithTypedNotification()
                startSyncLoop()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSyncLoop()
        serviceJob.cancel()
    }

    private fun startForegroundWithTypedNotification() {
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ typed foreground service enforcement
            // Traces to: [REQ-VER-001]
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            // Legacy fallback for API Level 28 to 33
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return

        syncJob = serviceScope.launch {
            val rootChecker = StandardRootChecker()
            val systemInfo = AndroidSystemInfoProvider(applicationContext)
            val capabilityProvider = DeviceCapabilityProvider(rootChecker, systemInfo)
            val dispatcher = TaskExecutionDispatcher()

            val settingsRepo = SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)
            val settings = settingsRepo.getSettings()
            val mtlsContext = DefaultMtlsSocketFactoryProvider.buildSocketFactory(settings)

            val authority = if (settings.wifiHost == "10.0.2.2" || settings.wifiHost == "127.0.0.1") "localhost" else null
            val client = WifiSyncClient(
                host = settings.wifiHost,
                port = settings.wifiPort,
                sslSocketFactory = mtlsContext?.socketFactory,
                authority = authority
            )
            wifiClient = client

            val nodeId = NodeIdProvider.getOrCreateNodeId(applicationContext)
            val batteryProvider = AndroidBatteryStatusProvider(applicationContext)
            val thermalListener = AndroidDeviceThermalStatusListener(applicationContext).apply {
                startListening()
            }
            val thermalGuard = ThermalSafetyGuard()

            val initialBattery = batteryProvider.getBatteryStatus()
            val initialThermal = thermalListener.currentThermalStatus

            val capabilities = capabilityProvider.buildCapabilities(
                currentTransport = NetworkTransportType.NETWORK_TRANSPORT_WIFI_LAN,
                isMetered = false,
                batteryTemperatureCelsius = initialBattery.temperatureCelsius,
                thermalStatus = initialThermal
            )

            // Step 1: Register node per [REQ-OP-001] with exponential backoff retry loop
            val regRequest = RegisterNodeRequest.newBuilder()
                .setNodeId(nodeId)
                .setCapabilities(capabilities)
                .setClientVersion("0.1.0")
                .build()

            var sessionToken = ""
            var retryDelayMs = SYNC_INTERVAL_MS
            val maxRetryDelayMs = 60_000L

            while (isActive && sessionToken.isEmpty()) {
                val regResult = client.registerNode(regRequest)
                if (regResult.isSuccess) {
                    val token = regResult.getOrNull()?.sessionToken ?: ""
                    if (token.isNotEmpty()) {
                        sessionToken = token
                        break
                    }
                }
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
            }

            if (!isActive || sessionToken.isEmpty()) {
                thermalListener.stopListening()
                return@launch
            }

            // Step 2: 2.0-second declarative synchronization loop per [REQ-OP-002]
            while (isActive) {
                val currentBattery = batteryProvider.getBatteryStatus()
                val currentThermal = thermalListener.currentThermalStatus
                val isThrottled = thermalGuard.isThermalThrottled(currentBattery.temperatureCelsius, currentThermal)

                val reportsToSend = mutableListOf<TaskExecutionReport>()
                while (completedReports.isNotEmpty()) {
                    completedReports.poll()?.let { reportsToSend.add(it) }
                }

                val syncRequest = SyncNodeStateRequest.newBuilder()
                    .setNodeId(nodeId)
                    .setSessionToken(sessionToken)
                    .addAllCompletedTasks(reportsToSend)
                    .build()

                val syncResult = client.syncNodeState(syncRequest)
                if (syncResult.isSuccess && !isThrottled) {
                    val response = syncResult.getOrNull()
                    response?.desiredTasksList?.forEach { desiredTask ->
                        // Dispatch assigned task concurrently in background
                        launch {
                            val result = dispatcher.dispatch(desiredTask)
                            val report = result.toTaskExecutionReport(
                                taskId = desiredTask.taskId,
                                runAttempt = desiredTask.runAttempt,
                                state = if (result.exitCode == 0) {
                                    TaskState.TASK_STATE_COMPLETED
                                } else {
                                    TaskState.TASK_STATE_FAILED
                                }
                            )
                            completedReports.add(report)
                        }
                    }
                }

                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
        serviceScope.launch {
            wifiClient?.shutdown(500L)
            wifiClient = null
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroGrid Worker")
            .setContentText("Participating in distributed cluster edge compute")
            .setSmallIcon(R.drawable.ic_stat_androgrid)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AndroGrid Compute Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
