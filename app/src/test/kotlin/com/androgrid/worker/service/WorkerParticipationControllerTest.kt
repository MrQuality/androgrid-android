package com.androgrid.worker.service

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.androgrid.worker.network.NetworkRouteMonitor
import com.androgrid.worker.telemetry.DeviceThermalStatusListener
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit and Robolectric tests verifying [DefaultWorkerParticipationController] demand-gated
 * monitor lifecycle, SharedPreferences persistence, and START_STICKY reconciliation.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [NFR-POW-001], ADR-008
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkerParticipationControllerTest {

    private lateinit var context: Context
    private lateinit var networkMonitor: NetworkRouteMonitor
    private lateinit var thermalListener: DeviceThermalStatusListener
    private lateinit var serviceController: ServiceController
    private lateinit var workScheduler: WorkScheduler
    private lateinit var coordinator: WorkerLifecycleCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            DefaultWorkerParticipationController.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = spykOrRealMonitor(connectivityManager)
        thermalListener = mockk(relaxed = true) {
            every { thermalStatusFlow } returns MutableStateFlow(0)
            every { currentThermalStatus } returns 0
        }
        serviceController = mockk(relaxed = true)
        workScheduler = mockk(relaxed = true)
        coordinator = WorkerLifecycleCoordinator(
            routeFlow = networkMonitor.currentRoute,
            serviceController = serviceController,
            workScheduler = workScheduler
        )
    }

    private fun spykOrRealMonitor(cm: ConnectivityManager): NetworkRouteMonitor {
        return io.mockk.spyk(NetworkRouteMonitor(cm))
    }

    @Test
    fun testDemandGatedMonitors_onlyActiveWhenComputeActiveOrUiStarted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = DefaultWorkerParticipationController(
            context = context,
            networkMonitor = networkMonitor,
            thermalListener = thermalListener,
            coordinator = coordinator,
            appScope = TestScope(dispatcher),
            ioDispatcher = dispatcher
        )
        advanceUntilIdle()

        // Initially idle and UI not started -> monitors must not start
        verify(exactly = 0) { networkMonitor.startMonitoring() }
        verify(exactly = 0) { thermalListener.startListening() }

        // UI starts -> monitors start
        controller.onUiStarted()
        verify(atLeast = 1) { networkMonitor.startMonitoring() }
        verify(atLeast = 1) { thermalListener.startListening() }

        // UI stops while compute is inactive -> monitors stop
        controller.onUiStopped()
        verify(atLeast = 1) { networkMonitor.stopMonitoring() }
        verify(atLeast = 1) { thermalListener.stopListening() }

        // Compute starts -> monitors stay active even without UI
        controller.startParticipation()
        controller.onUiStarted()
        controller.onUiStopped()
        assertThat(controller.isComputeActive.value).isTrue()
        // Stop compute while UI is gone -> monitors stop
        controller.stopParticipation()
        assertThat(controller.isComputeActive.value).isFalse()
        verify(atLeast = 2) { thermalListener.stopListening() }
    }

    @Test
    fun testStickyRestartSyncAndPersistence_restoresActiveState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val controller1 = DefaultWorkerParticipationController(
            context = context,
            networkMonitor = networkMonitor,
            thermalListener = thermalListener,
            coordinator = coordinator,
            appScope = scope,
            ioDispatcher = dispatcher
        )
        scope.advanceUntilIdle()

        // Sticky service restart notifies controller
        controller1.syncActiveFromServiceStart()
        scope.advanceUntilIdle()

        assertThat(controller1.isComputeActive.value).isTrue()

        // New controller instance after process recreation hydrates persisted true state
        val controller2 = DefaultWorkerParticipationController(
            context = context,
            networkMonitor = networkMonitor,
            thermalListener = thermalListener,
            coordinator = coordinator,
            appScope = scope,
            ioDispatcher = dispatcher
        )
        scope.advanceUntilIdle()
        assertThat(controller2.isComputeActive.value).isTrue()
    }

    @Test
    fun testRapidParticipationToggles_persistFinalAuthoritativeState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val controller = DefaultWorkerParticipationController(
            context = context,
            networkMonitor = networkMonitor,
            thermalListener = thermalListener,
            coordinator = coordinator,
            appScope = scope,
            ioDispatcher = dispatcher
        )
        scope.advanceUntilIdle()

        // Rapid toggles before IO coroutines drain
        controller.startParticipation()
        controller.stopParticipation()
        controller.startParticipation()
        controller.stopParticipation()
        scope.advanceUntilIdle()

        assertThat(controller.isComputeActive.value).isFalse()

        // Rehydrate from SharedPreferences to verify disk matches final in-memory state
        val rehydrated = DefaultWorkerParticipationController(
            context = context,
            networkMonitor = networkMonitor,
            thermalListener = thermalListener,
            coordinator = coordinator,
            appScope = scope,
            ioDispatcher = dispatcher
        )
        scope.advanceUntilIdle()
        assertThat(rehydrated.isComputeActive.value).isFalse()
    }
}
