package com.androgrid.worker.automation

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.androgrid.worker.AndroGridApplication
import com.androgrid.worker.network.InMemoryClusterSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Verifies [TestAutomationReceiver] routes participation intents through
 * [AndroGridApplication.participationController] when running under [AndroGridApplication].
 *
 * Traces to: [INV-001], [INV-002], [REQ-OP-001], [REQ-OP-002], ADR-008
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AndroGridApplication::class)
class TestAutomationReceiverApplicationTest {

    private lateinit var app: AndroGridApplication
    private lateinit var receiver: TestAutomationReceiver

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as AndroGridApplication
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        receiver = TestAutomationReceiver(
            settingsRepositoryProvider = { InMemoryClusterSettingsRepository() }
        )
    }

    @Test
    fun testParticipationIntents_updateApplicationParticipationControllerState() {
        assertThat(app.participationController.isComputeActive.value).isFalse()

        receiver.onReceive(app, Intent(TestAutomationReceiver.ACTION_START_PARTICIPATION))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertThat(app.participationController.isComputeActive.value).isTrue()

        receiver.onReceive(app, Intent(TestAutomationReceiver.ACTION_STOP_PARTICIPATION))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertThat(app.participationController.isComputeActive.value).isFalse()
    }
}
