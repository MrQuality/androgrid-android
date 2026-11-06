package com.androgrid.worker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.androgrid.worker.automation.PkiCredentialLoader
import com.androgrid.worker.automation.TestAutomationReceiver
import com.androgrid.worker.capability.AndroidBatteryStatusProvider
import com.androgrid.worker.capability.AndroidSystemInfoProvider
import com.androgrid.worker.capability.DeviceCapabilityProvider
import com.androgrid.worker.capability.StandardRootChecker
import com.androgrid.worker.network.ClusterSettingsRepository
import com.androgrid.worker.network.NetworkRoute
import com.androgrid.worker.network.NetworkRouteMonitor
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.androgrid.worker.service.AndroidServiceController
import com.androgrid.worker.service.AndroidWorkScheduler
import com.androgrid.worker.service.WorkerLifecycleCoordinator
import kotlinx.coroutines.launch
import java.io.File

/**
 * Interactive Worker Dashboard Activity providing operator observability and control.
 * Handles Android 13+ POST_NOTIFICATIONS runtime permissions and displays live telemetry.
 *
 * Traces to: Section 6.1, [REQ-VER-001], [REQ-VER-002], [INV-002]
 */
class MainActivity : ComponentActivity() {

    internal var settingsRepository: ClusterSettingsRepository? = null

    private lateinit var viewModel: WorkerDashboardViewModel
    private lateinit var networkMonitor: NetworkRouteMonitor

    private lateinit var statusBannerView: TextView
    private lateinit var nodeIdView: TextView
    private lateinit var hardwareView: TextView
    private lateinit var osView: TextView
    private lateinit var sandboxView: TextView
    private lateinit var batteryView: TextView
    private lateinit var telemetryView: TextView
    private lateinit var toggleButton: Button
    private lateinit var settingsButton: Button

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission evaluated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initDependencies()
        setupContentView()
        requestNotificationPermission()
        observeState()
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomationIntent(intent)
    }

    internal fun getDashboardViewModel(): WorkerDashboardViewModel = viewModel

    private fun handleAutomationIntent(intent: Intent?) {
        if (intent == null) return
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return

        val repo = settingsRepository ?: SharedPreferencesClusterSettingsRepository.getInstance(applicationContext)

        when (intent.action) {
            TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT -> {
                val current = repo.getSettings()
                val wifiHost = intent.getStringExtra(TestAutomationReceiver.EXTRA_WIFI_HOST) ?: current.wifiHost
                val wifiPort = intent.getIntExtra(TestAutomationReceiver.EXTRA_WIFI_PORT, current.wifiPort)
                val cellularHost = intent.getStringExtra(TestAutomationReceiver.EXTRA_CELLULAR_HOST) ?: current.cellularHost
                val cellularPort = intent.getIntExtra(TestAutomationReceiver.EXTRA_CELLULAR_PORT, current.cellularPort)

                val caCertPem = intent.getStringExtra(TestAutomationReceiver.EXTRA_CA_CERT_PEM) ?: current.caCertPem
                val clientCertPem = intent.getStringExtra(TestAutomationReceiver.EXTRA_CLIENT_CERT_PEM) ?: current.clientCertPem
                val clientKeyPem = intent.getStringExtra(TestAutomationReceiver.EXTRA_CLIENT_KEY_PEM) ?: current.clientKeyPem

                repo.updateSettings(
                    current.copy(
                        wifiHost = wifiHost,
                        wifiPort = wifiPort,
                        cellularHost = cellularHost,
                        cellularPort = cellularPort
                    )
                )

                val pkiDirExtra = intent.getStringExtra(TestAutomationReceiver.EXTRA_PKI_DIR)
                val caPath = intent.getStringExtra(TestAutomationReceiver.EXTRA_CA_CERT_PATH)
                val certPath = intent.getStringExtra(TestAutomationReceiver.EXTRA_CLIENT_CERT_PATH)
                val keyPath = intent.getStringExtra(TestAutomationReceiver.EXTRA_CLIENT_KEY_PATH)

                if (pkiDirExtra != null) {
                    PkiCredentialLoader.loadFromDirectory(File(pkiDirExtra), repo)
                } else if (caPath != null && certPath != null && keyPath != null) {
                    PkiCredentialLoader.loadFromPaths(caPath, certPath, keyPath, repo)
                } else if (caCertPem.isNotBlank() && clientCertPem.isNotBlank() && clientKeyPem.isNotBlank()) {
                    repo.updateCertificates(caCertPem, clientCertPem, clientKeyPem)
                } else {
                    PkiCredentialLoader.syncCanonicalDirectoryIfPresent(applicationContext, repo)
                }

                val autoStart = intent.getBooleanExtra(TestAutomationReceiver.EXTRA_AUTO_START, false)
                if (autoStart) {
                    viewModel.startParticipation()
                }
            }
            TestAutomationReceiver.ACTION_START_PARTICIPATION -> {
                viewModel.startParticipation()
            }
            TestAutomationReceiver.ACTION_STOP_PARTICIPATION -> {
                viewModel.stopParticipation()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stopMonitoring()
    }

    private fun initDependencies() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = NetworkRouteMonitor(connectivityManager)
        networkMonitor.startMonitoring()

        val rootChecker = StandardRootChecker()
        val systemInfo = AndroidSystemInfoProvider(applicationContext)
        val capabilityProvider = DeviceCapabilityProvider(rootChecker, systemInfo)
        val batteryProvider = AndroidBatteryStatusProvider(applicationContext)

        val serviceController = AndroidServiceController(applicationContext)
        val workScheduler = AndroidWorkScheduler(applicationContext)
        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = networkMonitor.currentRoute,
            serviceController = serviceController,
            workScheduler = workScheduler
        )

        val serial = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Build.SERIAL
        } else {
            "worker_${Build.MODEL.replace(" ", "_")}"
        }

        viewModel = WorkerDashboardViewModel(
            nodeId = serial,
            routeFlow = networkMonitor.currentRoute,
            capabilityProvider = capabilityProvider,
            batteryProvider = batteryProvider,
            coordinator = coordinator
        )
    }

    private fun setupContentView() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val titleView = TextView(this).apply {
            text = "AndroGrid Edge Compute"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootLayout.addView(titleView)

        statusBannerView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#333333"))
        }
        val bannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 32, 0, 32)
        }
        rootLayout.addView(statusBannerView, bannerParams)

        nodeIdView = createMetricView()
        hardwareView = createMetricView()
        osView = createMetricView()
        sandboxView = createMetricView()
        batteryView = createMetricView()
        telemetryView = createMetricView()

        rootLayout.addView(nodeIdView)
        rootLayout.addView(hardwareView)
        rootLayout.addView(osView)
        rootLayout.addView(sandboxView)
        rootLayout.addView(batteryView)
        rootLayout.addView(telemetryView)

        toggleButton = Button(this).apply {
            text = "Start Compute Participation"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (viewModel.uiState.value.isComputeActive) {
                    viewModel.stopParticipation()
                } else {
                    viewModel.startParticipation()
                }
            }
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 48, 0, 0)
        }
        rootLayout.addView(toggleButton, buttonParams)

        settingsButton = Button(this).apply {
            text = "Cluster Network & mTLS Settings"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
        }
        val settingsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 24, 0, 0)
        }
        rootLayout.addView(settingsButton, settingsParams)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)
    }

    private fun createMetricView(): TextView {
        return TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 12, 0, 12)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                renderState(state)
            }
        }
    }

    private fun renderState(state: WorkerDashboardState) {
        statusBannerView.text = state.statusBannerText

        val bannerColor = when (state.route) {
            is NetworkRoute.LanConnected -> Color.parseColor("#2E7D32") // Green
            is NetworkRoute.CellularWan -> Color.parseColor("#1565C0")  // Blue
            is NetworkRoute.Offline -> Color.parseColor("#424242")      // Gray
        }
        statusBannerView.setBackgroundColor(bannerColor)

        nodeIdView.text = "Node ID: ${state.nodeId}"
        hardwareView.text = "Hardware: ${state.cpuCores} Cores | ${state.totalRamFormatted()} RAM"
        osView.text = "Platform: Android ${state.osVersion} (${state.abi})"
        sandboxView.text = "Sandbox Boundary: ${if (state.isRooted) "Rooted Access" else "WASM / Toybox Guarded"}"
        batteryView.text = "Power State: ${state.batteryFormatted()}"
        telemetryView.text = "Tasks Executed: ${state.tasksCompletedCount}"

        if (state.isComputeActive) {
            toggleButton.text = "Stop Compute Participation"
            toggleButton.setBackgroundColor(Color.parseColor("#C62828")) // Red
        } else {
            toggleButton.text = "Start Compute Participation"
            toggleButton.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
        }
    }
}
