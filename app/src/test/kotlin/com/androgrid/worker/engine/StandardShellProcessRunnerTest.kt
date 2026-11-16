package com.androgrid.worker.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests verifying [StandardShellProcessRunner] cooperative cancellation and
 * child process cleanup on coroutine cancellation.
 *
 * Traces to: [INV-006], [NFR-POW-001], [NFR-PERF-001]
 */
class StandardShellProcessRunnerTest {

    @Test
    fun runProcess_coroutineCancelled_terminatesChildProcessAndRethrowsCancellation(): Unit = runBlocking {
        val spawnedProcess = AtomicReference<Process?>(null)
        val runner = StandardShellProcessRunner(
            onProcessStarted = { process -> spawnedProcess.set(process) }
        )
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val command = if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30")
        } else {
            listOf("sleep", "30")
        }

        var caughtCancellation = false
        val job = launch(Dispatchers.Default) {
            try {
                runner.runProcess(
                    command = command,
                    environment = emptyMap(),
                    timeoutSeconds = 60L
                )
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        delay(150)
        val process = spawnedProcess.get()
        assertThat(process).isNotNull()

        val cancelStartMs = System.currentTimeMillis()
        job.cancel()
        withTimeout(3000L) {
            job.join()
        }
        val elapsedMs = System.currentTimeMillis() - cancelStartMs

        assertThat(job.isCancelled).isTrue()
        assertThat(caughtCancellation).isTrue()
        assertThat(elapsedMs).isLessThan(2000L)
        assertThat(process!!.isAlive).isFalse()
    }
}
