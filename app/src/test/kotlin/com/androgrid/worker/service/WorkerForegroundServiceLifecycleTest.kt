package com.androgrid.worker.service

import android.os.Build
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric lifecycle tests verifying [WorkerForegroundService] API 34+
 * `onTimeout` handling and SDK 28/34 compatibility.
 *
 * Traces to: [REQ-VER-001], [REQ-OP-002]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class WorkerForegroundServiceLifecycleTest {

    @Test
    fun handleServiceTimeout_cancelsActiveTasksAndStopsSelf() {
        val controller = Robolectric.buildService(WorkerForegroundService::class.java).create()
        val service = controller.get()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val blocker = CompletableDeferred<Unit>()
        val job = scope.launch { blocker.await() }
        service.activeJobs["timeout-task-1"] = job

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.onTimeout(1001)
        } else {
            service.handleServiceTimeout(1001)
        }

        assertThat(job.isCancelled).isTrue()
        assertThat(service.activeJobs).isEmpty()
        assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()

        // Also verify handleServiceTimeout stops even when invoked with a stale startId
        service.handleServiceTimeout(1)
        assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
    }
}
