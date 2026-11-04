package com.androgrid.worker.service

import com.androgrid.worker.network.NetworkRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for Worker Lifecycle Coordinator.
 * Verifies route transitions according to Section 6.1 Dual-Route Connection State Matrix.
 *
 * Traces to: Section 6.1, [INV-001], [REQ-OP-001], [REQ-OP-003]
 */
class WorkerLifecycleCoordinatorTest {

    private class FakeServiceController : ServiceController {
        val startServiceCalled = AtomicBoolean(false)
        val stopServiceCalled = AtomicBoolean(false)

        override fun startWifiSyncService() {
            startServiceCalled.set(true)
            stopServiceCalled.set(false)
        }

        override fun stopWifiSyncService() {
            stopServiceCalled.set(true)
            startServiceCalled.set(false)
        }
    }

    private class FakeWorkScheduler : WorkScheduler {
        val scheduleCellularWorkCalled = AtomicInteger(0)
        val cancelCellularWorkCalled = AtomicInteger(0)

        override fun scheduleCellularSpotWork() {
            scheduleCellularWorkCalled.incrementAndGet()
        }

        override fun cancelCellularSpotWork() {
            cancelCellularWorkCalled.incrementAndGet()
        }
    }

    @Test
    fun testTransitionToLanConnected_startsFgsAndCancelsCellular() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val serviceController = FakeServiceController()
        val workScheduler = FakeWorkScheduler()

        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = routeFlow,
            serviceController = serviceController,
            workScheduler = workScheduler
        )

        coordinator.handleRouteTransition(NetworkRoute.LanConnected(isMetered = false))

        assertThat(serviceController.startServiceCalled.get()).isTrue()
        assertThat(serviceController.stopServiceCalled.get()).isFalse()
        assertThat(workScheduler.cancelCellularWorkCalled.get()).isEqualTo(1)
    }

    @Test
    fun testTransitionToCellularWan_stopsFgsAndSchedulesCellular() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val serviceController = FakeServiceController()
        val workScheduler = FakeWorkScheduler()

        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = routeFlow,
            serviceController = serviceController,
            workScheduler = workScheduler
        )

        coordinator.handleRouteTransition(NetworkRoute.CellularWan(isMetered = true))

        assertThat(serviceController.stopServiceCalled.get()).isTrue()
        assertThat(serviceController.startServiceCalled.get()).isFalse()
        assertThat(workScheduler.scheduleCellularWorkCalled.get()).isEqualTo(1)
    }

    @Test
    fun testTransitionToOffline_stopsFgsAndCancelsCellular() = runTest {
        val routeFlow = MutableStateFlow<NetworkRoute>(NetworkRoute.Offline)
        val serviceController = FakeServiceController()
        val workScheduler = FakeWorkScheduler()

        val coordinator = WorkerLifecycleCoordinator(
            routeFlow = routeFlow,
            serviceController = serviceController,
            workScheduler = workScheduler
        )

        coordinator.handleRouteTransition(NetworkRoute.Offline)

        assertThat(serviceController.stopServiceCalled.get()).isTrue()
        assertThat(workScheduler.cancelCellularWorkCalled.get()).isEqualTo(1)
    }
}
