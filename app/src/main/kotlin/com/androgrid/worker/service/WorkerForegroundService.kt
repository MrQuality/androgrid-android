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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.androgrid.worker.AndroGridApplication
import com.androgrid.worker.R
import com.androgrid.v1.FetchArtifactRequest
import com.androgrid.v1.FetchArtifactResponse
import com.androgrid.v1.NetworkTransportType
import com.androgrid.v1.RegisterNodeRequest
import com.androgrid.v1.SyncNodeStateRequest
import com.androgrid.v1.SyncNodeStateResponse
import com.androgrid.v1.TaskExecutionReport
import com.androgrid.v1.TaskState
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.NodeIdProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.enrollment.AndroidKeyStoreEnrollmentProvider
import com.androgrid.worker.enrollment.CredentialLifecycleManager
import com.androgrid.worker.enrollment.RenewalOutcome
import com.androgrid.worker.engine.TaskExecutionDispatcher
import com.androgrid.worker.network.ArtifactResolution
import com.androgrid.worker.network.ArtifactResolver
import com.androgrid.worker.network.DefaultMtlsSocketFactoryProvider
import com.androgrid.worker.network.OkHttpBlobFetcher
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.network.WifiSyncClient
import com.androgrid.worker.telemetry.AndroidDeviceThermalStatusListener
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.androgrid.worker.telemetry.SharedPreferencesTaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerEntry
import com.androgrid.worker.telemetry.TaskLedgerRepository
import com.androgrid.worker.telemetry.TaskLedgerStatus
import com.androgrid.worker.telemetry.ThermalSafetyGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

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
        private const val TAG: String = "WorkerForegroundService"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var syncJob: Job? = null

    private var wifiClient: WifiSyncClient? = null
    private var wifiBlobClient: okhttp3.OkHttpClient? = null
    private val completedReports = ConcurrentLinkedQueue<TaskExecutionReport>()
    private val inFlightTasks = ConcurrentHashMap.newKeySet<String>()
    internal val activeJobs = ConcurrentHashMap<String, Job>()

    private fun resolveLedgerRepository(): TaskLedgerRepository {
        return (applicationContext as? AndroGridApplication)?.taskLedgerRepository
            ?: SharedPreferencesTaskLedgerRepository.getInstance(applicationContext)
    }

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
                (applicationContext as? AndroGridApplication)
                    ?.participationController
                    ?.syncActiveFromServiceStart()
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

    /**
     * Handles Android 14+ (API 34+) `dataSync` foreground service timeout preemption ([REQ-VER-001]).
     * Cancels active compute jobs and stops the service cleanly before the OS raises an exception.
     */
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleServiceTimeout(startId)
    }

    /**
     * Gracefully cancels active compute tasks, updates the service notification, and stops the service.
     *
     * @param startId Android service start ID passed to [stopSelf].
     */
    internal fun handleServiceTimeout(startId: Int) {
        Log.w(TAG, "Foreground service dataSync timeout reached (startId=$startId); stopping cleanly")
        stopSyncLoop()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            val app = application as? AndroGridApplication
            val settingsRepo = app?.settingsRepository
                ?: SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)
            val lifecycleManager = app?.credentialLifecycleManager
                ?: CredentialLifecycleManager(settingsRepo)

            lifecycleManager.reconcileOnStartup()

            // Only restart the session when endpoint or credential presence changes,
            // NOT when clientCertPem/clientKeyPem rotates in-band.
            settingsRepo.settingsFlow
                .distinctUntilChangedBy { Triple(it.wifiHost, it.wifiPort, it.hasMtlsCredentials) }
                .collectLatest { endpointSettings ->
                    val settings = settingsRepo.getSettings()
                    val mtlsContext = DefaultMtlsSocketFactoryProvider.buildSocketFactory(settings)
                    if (mtlsContext == null) {
                        updateNotification("Worker idle - Awaiting cluster enrollment / mTLS credentials")
                        wifiClient?.shutdown(100L)
                        wifiClient = null
                        shutdownBlobClient(wifiBlobClient)
                        wifiBlobClient = null
                        return@collectLatest
                    }

                    updateNotification("Participating in distributed cluster edge compute")
                    val authority = if (OkHttpBlobFetcher.isLoopbackHost(settings.wifiHost)) "localhost" else null
                    val initialAlias = settings.clientKeyPem.removePrefix("keystore:")
                        .takeIf { settings.clientKeyPem.startsWith("keystore:") }
                        ?: AndroidKeyStoreEnrollmentProvider.DEFAULT_ENROLLMENT_ALIAS
                    lifecycleManager.acquireGeneration(initialAlias)

                    val activeClientRef = AtomicReference(
                        WifiSyncClient(
                            host = settings.wifiHost,
                            port = settings.wifiPort,
                            sslSocketFactory = mtlsContext.socketFactory,
                            authority = authority
                        )
                    )
                    val activeAliasRef = AtomicReference(initialAlias)
                    wifiClient = activeClientRef.get()
                    val activeBlobClientRef = AtomicReference<okhttp3.OkHttpClient?>(
                        if (settings.hasMtlsCredentials) {
                            OkHttpBlobFetcher.buildMtlsClient(
                                mtlsContext = mtlsContext,
                                allowLocalhostLoopback = authority != null,
                                ephemeralPool = false
                            )
                        } else {
                            null
                        }
                    )
                    wifiBlobClient = activeBlobClientRef.get()

                    val nodeId = NodeIdProvider.getOrCreateNodeId(applicationContext)
                    val batteryProvider = AndroidBatteryStatusProvider(applicationContext)
                    val thermalListener = AndroidDeviceThermalStatusListener(applicationContext).apply {
                        startListening()
                    }
                    val thermalGuard = ThermalSafetyGuard()

                    try {
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
                            val regResult = activeClientRef.get()!!.registerNode(regRequest)
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
                            return@collectLatest
                        }

                        val ledgerRepository = resolveLedgerRepository()
                        val artifactResolver = app?.artifactResolver ?: ArtifactResolver()
                        val taskProcessor = WifiSyncTaskProcessor(
                            dispatcher = dispatcher,
                            ledgerRepository = ledgerRepository,
                            inFlightTasks = inFlightTasks,
                            completedReports = completedReports,
                            artifactResolver = artifactResolver,
                            nodeId = nodeId,
                            activeJobs = activeJobs,
                            blobFetcher = if (settings.hasMtlsCredentials) {
                                { sha ->
                                    val genAlias = activeAliasRef.get()
                                    val httpClient = activeBlobClientRef.get()
                                        ?: throw IllegalStateException("Wi-Fi blob OkHttpClient not initialized")
                                    lifecycleManager.acquireGeneration(genAlias)
                                    try {
                                        OkHttpBlobFetcher.fetch(
                                            httpClient,
                                            "https://${settings.wifiHost}:${settings.wifiPort}/v1/blobs/$sha"
                                        )
                                    } finally {
                                        lifecycleManager.releaseGeneration(genAlias)
                                    }
                                }
                            } else {
                                null
                            },
                            fetchArtifactRpc = { req ->
                                val genAlias = activeAliasRef.get()
                                val clientForFetch = activeClientRef.get()!!
                                lifecycleManager.acquireGeneration(genAlias)
                                try {
                                    clientForFetch.fetchArtifact(req)
                                } finally {
                                    lifecycleManager.releaseGeneration(genAlias)
                                }
                            }
                        )

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

                            // Check in-band certificate renewal without dropping session_token or active tasks
                            val currentSettings = settingsRepo.getSettings()
                            val nowSecs = System.currentTimeMillis() / 1000L
                            var syncResult: Result<com.androgrid.v1.SyncNodeStateResponse>? = null
                            if (nowSecs >= lifecycleManager.getNextEligibleRenewalEpochSeconds() &&
                                lifecycleManager.isWithinRenewalWindow(currentSettings.clientCertPem)
                            ) {
                                val oldClient = activeClientRef.get()!!
                                val oldAlias = activeAliasRef.get()
                                val renewalOutcome = lifecycleManager.renewIfDue(
                                    nodeId = nodeId,
                                    renewRpc = { req -> oldClient.renewCertificate(req) },
                                    trialCandidateRpc = { candidateAlias, _ ->
                                        val candidateCtx = lifecycleManager.createCandidateMtlsContext(
                                            candidateAlias,
                                            currentSettings.caCertPem
                                        )
                                        val candidateClient = WifiSyncClient(
                                            host = currentSettings.wifiHost,
                                            port = currentSettings.wifiPort,
                                            sslSocketFactory = candidateCtx.socketFactory,
                                            authority = authority
                                        )
                                        val trialSync = candidateClient.syncNodeState(syncRequest)
                                        if (trialSync.isSuccess) {
                                            Result.success(candidateClient to trialSync.getOrThrow())
                                        } else {
                                            candidateClient.shutdown(100L)
                                            Result.failure(
                                                trialSync.exceptionOrNull()
                                                    ?: IllegalStateException("Trial SyncNodeState failed")
                                            )
                                        }
                                    }
                                )
                                if (renewalOutcome is RenewalOutcome.Renewed<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val pair =
                                        renewalOutcome.trialResult as Pair<WifiSyncClient, com.androgrid.v1.SyncNodeStateResponse>
                                    val candidateClient = pair.first
                                    val trialResp = pair.second
                                    lifecycleManager.acquireGeneration(renewalOutcome.newAlias)
                                    activeClientRef.set(candidateClient)
                                    activeAliasRef.set(renewalOutcome.newAlias)
                                    wifiClient = candidateClient
                                    val newBlobClient = OkHttpBlobFetcher.buildMtlsClient(
                                        mtlsContext = lifecycleManager.createCandidateMtlsContext(
                                            renewalOutcome.newAlias,
                                            currentSettings.caCertPem
                                        ),
                                        allowLocalhostLoopback = authority != null,
                                        ephemeralPool = false
                                    )
                                    val oldBlobClient = activeBlobClientRef.getAndSet(newBlobClient)
                                    wifiBlobClient = newBlobClient
                                    CoroutineScope(Dispatchers.IO).launch {
                                        shutdownBlobClient(oldBlobClient)
                                        oldClient.shutdown(500L)
                                        lifecycleManager.releaseGeneration(oldAlias)
                                    }
                                    syncResult = Result.success(trialResp)
                                }
                            }

                            val effectiveSyncResult = syncResult ?: activeClientRef.get()!!.syncNodeState(syncRequest)
                            if (effectiveSyncResult.isSuccess) {
                                val response = effectiveSyncResult.getOrNull()
                                if (response != null) {
                                    taskProcessor.applySyncResponse(this, response, isThrottled)
                                }
                            }

                            delay(SYNC_INTERVAL_MS)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            thermalListener.stopListening()
                            activeClientRef.get()?.shutdown(100L)
                            lifecycleManager.releaseGeneration(activeAliasRef.get())
                        }
                    }
                }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroGrid Worker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_androgrid)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun shutdownBlobClient(client: okhttp3.OkHttpClient?) {
        if (client == null) return
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
        val blobClientToClose = wifiBlobClient
        wifiBlobClient = null
        shutdownBlobClient(blobClientToClose)
        val clientToClose = wifiClient
        wifiClient = null
        if (clientToClose != null) {
            // Shut down outside serviceScope so serviceJob.cancel() in onDestroy() cannot abort teardown
            CoroutineScope(Dispatchers.IO).launch {
                clientToClose.shutdown(100L)
            }
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

/**
 * Coordinates eligibility checks, in-flight lease deduplication, outbound report queuing,
 * and durable [TaskLedgerRepository] commits for tasks assigned on the Wi-Fi LAN sync loop.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [INV-004], [NFR-POW-001], [NFR-SEC-001], ADR-008
 */
internal class WifiSyncTaskProcessor(
    private val dispatcher: TaskExecutionDispatcher,
    private val ledgerRepository: TaskLedgerRepository,
    private val inFlightTasks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    private val completedReports: ConcurrentLinkedQueue<TaskExecutionReport> = ConcurrentLinkedQueue(),
    private val artifactResolver: ArtifactResolver = ArtifactResolver(),
    private val nodeId: String,
    private val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap(),
    private val blobFetcher: (suspend (String) -> ByteArray)? = null,
    private val fetchArtifactRpc: (suspend (FetchArtifactRequest) -> FetchArtifactResponse)? = null
) {
    /**
     * Cancels an active in-flight task by [taskId] if present in [activeJobs].
     *
     * @param taskId unique orchestrator task identifier.
     * @return `true` if a running job was cancelled, `false` if no active job matched.
     */
    fun cancelTask(taskId: String): Boolean {
        val job = activeJobs.remove(taskId) ?: return false
        job.cancel()
        return true
    }

    /**
     * Applies cancellation directives and eligible task launches from [response].
     * Processes `cancel_task_ids` before `desired_tasks` and regardless of thermal throttling
     * so cancelled tasks terminate promptly.
     *
     * @param scope coroutine scope for launching eligible tasks.
     * @param response orchestrator `SyncNodeStateResponse` payload.
     * @param isThrottled whether the worker is currently thermally throttled.
     */
    suspend fun applySyncResponse(
        scope: CoroutineScope,
        response: SyncNodeStateResponse,
        isThrottled: Boolean
    ) {
        response.cancelTaskIdsList.forEach { taskId ->
            cancelTask(taskId)
        }
        if (!isThrottled) {
            response.desiredTasksList.forEach { desiredTask ->
                launchTaskIfEligible(scope, desiredTask)
            }
        }
    }

    /**
     * Checks ledger/in-flight eligibility for [desiredTask] and, if eligible, launches execution
     * in [scope]. Resolves content-addressed artifacts over mTLS `50051` (`/v1/blobs/{sha256}`
     * via OkHttp, or legacy `FetchArtifact` RPC fallback) before invoking
     * [TaskExecutionDispatcher] ([INV-002], [INV-003]). Returns the launched [Job], or
     * `null` if skipped as duplicate/recorded.
     */
    suspend fun launchTaskIfEligible(
        scope: CoroutineScope,
        desiredTask: com.androgrid.v1.TaskSpec
    ): Job? {
        val attemptKey = "${desiredTask.taskId}:${desiredTask.runAttempt}"
        if (ledgerRepository.isRecorded(desiredTask.taskId, desiredTask.runAttempt)) {
            return null
        }
        if (!inFlightTasks.add(attemptKey)) {
            return null
        }
        val priorJobDeferred = CompletableDeferred<Job?>()
        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                priorJobDeferred.await()?.cancelAndJoin()
                val resolvedTask = when (
                    val resolution = artifactResolver.resolve(desiredTask, nodeId, blobFetcher) { req ->
                        val rpc = fetchArtifactRpc
                            ?: throw IllegalStateException("FetchArtifact RPC not configured")
                        rpc.invoke(req)
                    }
                ) {
                    is ArtifactResolution.Ready -> resolution.task
                    is ArtifactResolution.TerminalFailure -> {
                        val failedReport = TaskExecutionReport.newBuilder()
                            .setTaskId(desiredTask.taskId)
                            .setRunAttempt(desiredTask.runAttempt)
                            .setState(TaskState.TASK_STATE_FAILED)
                            .setExitCode(-1)
                            .setErrorCode(resolution.errorCode)
                            .setStderrBytes(com.google.protobuf.ByteString.copyFromUtf8(resolution.errorMessage))
                            .setExecutionTimeMs(0L)
                            .build()
                        completedReports.add(failedReport)
                        withContext(NonCancellable) {
                            ledgerRepository.recordTaskExecution(
                                TaskLedgerEntry(
                                    taskId = desiredTask.taskId,
                                    runAttempt = desiredTask.runAttempt,
                                    route = "WIFI_LAN",
                                    status = TaskLedgerStatus.FAILED,
                                    exitCode = -1,
                                    errorCode = resolution.errorCode,
                                    executionDurationMs = 0L,
                                    completedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                        return@launch
                    }
                    is ArtifactResolution.TransientError -> {
                        // Do not record in ledger; release inFlightTasks in finally so the next sync tick retries.
                        return@launch
                    }
                }

                val result = dispatcher.dispatch(resolvedTask)
                val isSuccess = result.isSuccess
                val report = result.toTaskExecutionReport(
                    taskId = resolvedTask.taskId,
                    runAttempt = resolvedTask.runAttempt,
                    state = if (isSuccess) {
                        TaskState.TASK_STATE_COMPLETED
                    } else {
                        TaskState.TASK_STATE_FAILED
                    }
                )
                // Enqueue outbound report before disk commit so sync is never delayed
                completedReports.add(report)
                withContext(NonCancellable) {
                    ledgerRepository.recordTaskExecution(
                        TaskLedgerEntry(
                            taskId = desiredTask.taskId,
                            runAttempt = desiredTask.runAttempt,
                            route = "WIFI_LAN",
                            status = if (isSuccess) {
                                TaskLedgerStatus.COMPLETED
                            } else {
                                TaskLedgerStatus.FAILED
                            },
                            exitCode = result.exitCode,
                            errorCode = result.errorCode,
                            executionDurationMs = result.executionDurationMs,
                            completedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: CancellationException) {
                // Propagate coroutine cancellation cleanly without inflating failed count
                throw e
            } finally {
                inFlightTasks.remove(attemptKey)
                activeJobs.remove(desiredTask.taskId, launchedJob)
            }
        }
        val priorJob = activeJobs.put(desiredTask.taskId, launchedJob)
        priorJob?.cancel()
        priorJobDeferred.complete(priorJob)
        if (!launchedJob.start() && launchedJob.isCancelled) {
            inFlightTasks.remove(attemptKey)
            activeJobs.remove(desiredTask.taskId, launchedJob)
        }
        return launchedJob
    }
}
