package com.androgrid.worker.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.androgrid.worker.automation.TestAutomationReceiver
import com.androgrid.worker.network.ClusterSettings
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.androgrid.worker.network.SharedPreferencesClusterSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [MainActivity] automation intent processing.
 * Verifies headless intent configuration and execution dispatch via ADB am start.
 *
 * Traces to: [INV-001], [INV-002], [REQ-OP-001], [REQ-OP-002]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityAutomationTest {

    private lateinit var repo: InMemoryClusterSettingsRepository

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        repo = InMemoryClusterSettingsRepository()
    }

    @Test
    fun testMainActivity_onCreateWithConfigureIntent_updatesSettings() {
        val intent = Intent(TestAutomationReceiver.ACTION_CONFIGURE_ENDPOINT).apply {
            putExtra(TestAutomationReceiver.EXTRA_WIFI_HOST, "10.0.2.2")
            putExtra(TestAutomationReceiver.EXTRA_WIFI_PORT, 50051)
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        val activity = controller.get()
        activity.settingsRepository = repo

        controller.create().start().resume()

        assertThat(repo.getSettings().wifiHost).isEqualTo("10.0.2.2")
        assertThat(repo.getSettings().wifiPort).isEqualTo(50051)

        controller.destroy()
    }

    @Test
    fun testMainActivity_onNewIntent_startAndStopParticipation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.settingsRepository = repo

        controller.create().start().resume()

        val startIntent = Intent(TestAutomationReceiver.ACTION_START_PARTICIPATION)
        controller.newIntent(startIntent)
        assertThat(activity.getDashboardViewModel().uiState.value.isComputeActive).isTrue()

        val stopIntent = Intent(TestAutomationReceiver.ACTION_STOP_PARTICIPATION)
        controller.newIntent(stopIntent)
        assertThat(activity.getDashboardViewModel().uiState.value.isComputeActive).isFalse()

        controller.destroy()
    }
}
