# ADR-009: Cooperative WASM Task Cancellation and Multi-Hour Foreground Service Resilience

## Status
Accepted

## Architectural Scope & Requirements
- **Traceability:** `[REQ-OP-002]`, `[REQ-VER-001]`, `[INV-004]`, `[INV-006]`, `[NFR-PERF-001]`, `[NFR-POW-001]`
- **Standard:** ISO/IEC/IEEE 29148
- **GitHub Issue:** #57

---

## Context

AndroGrid executes three compute backends behind `TaskExecutionDispatcher` (`app/src/main/kotlin/com/androgrid/worker/engine/TaskExecutionDispatcher.kt`): `WasmComputeEngine`, `ToyboxUserEngine`, and `RootShellEngine`. On the Wi-Fi LAN path, `WorkerForegroundService` (`app/src/main/kotlin/com/androgrid/worker/service/WorkerForegroundService.kt`) runs a 2.0-second `SyncNodeState` loop (`[REQ-OP-002]`) and delegates task execution to `WifiSyncTaskProcessor`.

Three architectural gaps prevented safe cooperative cancellation and multi-hour execution resilience:

1. **Unstructured Dispatch & Uninterruptible WASM Interpreter Loop:**
   `WasmComputeEngine.execute()` (`app/src/main/kotlin/com/androgrid/worker/engine/WasmComputeEngine.kt:49-106`) dispatched execution inside an unstructured `CoroutineScope(Dispatchers.Default).async` block (`lines 75-76`) and caught all `Throwable`s (`line 83`) into `TaskExecutionResult` failures. Cancelling the caller's coroutine `Job` neither stopped Chicory 1.7.5's interpreter loop nor propagated `CancellationException`.
2. **Orphaned OS Child Processes on Coroutine Cancellation:**
   `StandardShellProcessRunner.runProcess()` (`app/src/main/kotlin/com/androgrid/worker/engine/ShellProcessRunner.kt:72-100`) blocked in `process.waitFor(effectiveTimeout, TimeUnit.SECONDS)` inside `withContext(Dispatchers.IO)` without `runInterruptible` or cancellation cleanup, leaving child processes running when a coroutine was cancelled externally.
3. **Missing `cancel_task_ids` Wiring & API 34+ `dataSync` FGS Timeout:**
   `WorkerForegroundService` is declared with `android:foregroundServiceType="dataSync"` (`app/src/main/AndroidManifest.xml:58-61`), which Android 14+ (API 34+, `[REQ-VER-001]`) limits to 6 hours per 24-hour window before invoking `Service.onTimeout(startId: Int)`. `WorkerForegroundService` did not override `onTimeout(startId: Int)`, tracked no active `Job` registry by `taskId`, and ignored `SyncNodeStateResponse.cancel_task_ids` (`app/src/main/proto/androgrid/v1/orchestrator.proto:208`).

---

## Empirical Validation (Technical Spike)

A JUnit technical spike against Chicory 1.7.5 verified:
- **Deterministic Interruption:** Attaching an `ExecutionListener` checking `(instructions and 0x3FF) == 0` on `TestWasmBytecodeFixtures.INFINITE_LOOP` across 10 cancel cycles achieved `10/10` clean cancellations via a private `CancelledTrap`, with `maxLatencyMs = 6 ms`, `avgLatencyMs = 0.6 ms` (threshold `< 100 ms`), and `executor.awaitTermination(2, TimeUnit.SECONDS) == true` (zero leaked threads).
- **Negligible Interpreter Overhead:** On `TestWasmBytecodeFixtures.FIBONACCI` (`fib(28)`), `baselineMs = 192.5894 ms` vs `guardedMs = 145.3161 ms` (within JIT warmup noise).
- **Critical Precondition:** `Instance.builder(module)` defaults to `withStart(true)` and executes `_start` synchronously during `.build()`. Keeping `.withStart(false)` is mandatory so cancellation handles and thread references bind before `_start` runs.

---

## Decisions Made

### 1. Cooperative Chicory Interruption (`WasmComputeEngine`)
- **Decision:** Replace detached `CoroutineScope(Dispatchers.Default).async` with structured `withContext(Dispatchers.Default)` + `withTimeout(effectiveTimeoutSeconds * 1000L)` (retaining the `180L` second fallback when `timeoutSeconds <= 0` for symmetry with `ToyboxUserEngine` and `RootShellEngine`). Capture the outer `callerJob = coroutineContext[Job]` before `withTimeout` and the inner `executionJob` inside `withTimeout`. Bind `workerThread.set(Thread.currentThread())` before `_start` and clear it in `finally`, with `executionJob?.invokeOnCompletion` setting `cancelled.set(true)` and interrupting `workerThread`. Attach a modulo-1024 `ExecutionListener` via `.withStart(false).withUnsafeExecutionListener(...)` that throws `WasmCancelledTrap` when `cancelled.get() || executionJob?.isActive == false || Thread.currentThread().isInterrupted`.
- **Exception Ordering:** Catch `TimeoutCancellationException` before `CancellationException`: if `callerJob?.isActive != false`, return `TaskExecutionResult(exitCode = -1, errorCode = "WASM_EXECUTION_TIMEOUT", ...)` so local timeouts are reported; otherwise rethrow `CancellationException`. Catch `WasmCancelledTrap` and `CancellationException` before `catch (t: Throwable)` and rethrow `CancellationException`.
- **Alternatives Rejected:** `Thread.interrupt()` alone (Chicory's interpreter loop does not poll `Thread.isInterrupted`); per-instruction `AtomicBoolean` checks (higher branch frequency); AOT native code signals (violates W^X policy).
- **Trade-offs:** Accepts up to 1023 WASM instructions of latency before cancellation triggers; blocking WASI host calls remain outside instruction dispatch.

### 2. Bounded Process Cancellation (`StandardShellProcessRunner`)
- **Decision:** Wrap blocking `process.waitFor(...)` inside `runInterruptible(Dispatchers.IO)` (or `withContext(Dispatchers.IO)` + `suspendCancellableCoroutine` with `invokeOnCancellation { process.destroyForcibly() }`) and ensure `finally` calls `process.destroyForcibly()` and joins `stdoutDrainer.join(200)` / `stderrDrainer.join(200)` whenever execution did not complete normally.
- **Alternatives Rejected:** Bare `finally` inside `withContext(Dispatchers.IO)` (does not unblock `process.waitFor`); two-stage SIGTERM then SIGKILL grace period (delays cellular/worker shutdown beyond `[NFR-POW-001]`).
- **Trade-offs:** Immediate `SIGKILL` (`destroyForcibly`) skips child SIGTERM cleanup hooks in favor of deterministic bounded teardown.

### 3. `cancel_task_ids` Registry (`WorkerForegroundService` & `WifiSyncTaskProcessor`)
- **Decision:** `WorkerForegroundService` owns `activeJobs: ConcurrentHashMap<String, Job>` keyed by `taskId` and passes it to `WifiSyncTaskProcessor`. `WifiSyncTaskProcessor.launchTaskIfEligible` launches with `scope.launch(start = CoroutineStart.LAZY)`, cancels any stale prior `Job` via `activeJobs.put(desiredTask.taskId, job)?.cancel()`, calls `job.start()`, and removes via two-argument `activeJobs.remove(desiredTask.taskId, job)` in `finally`. `WifiSyncTaskProcessor.cancelTask(taskId)` cancels and removes the matching `Job`. On `SyncNodeStateResponse`, `cancelTaskIdsList` is processed under `if (effectiveSyncResult.isSuccess)` *outside* the `!isThrottled` gate and *before* `desiredTasksList`.
- **Alternatives Rejected:** Keying by `"$taskId:$runAttempt"` (`SyncNodeStateResponse.cancel_task_ids` only carries `taskId`).
- **Trade-offs:** Assumes at most one active attempt per `taskId` on a worker node.

### 4. API 34+ `onTimeout(startId: Int)` Resilience (`WorkerForegroundService`)
- **Decision:** Retain `android:foregroundServiceType="dataSync"` (`ADR-003-typed-foreground-service-and-workmanager.md`) and override `onTimeout(startId: Int)` (matching `compileSdk = 34` in `app/build.gradle.kts:12`), delegating to `handleServiceTimeout(startId: Int)` which logs a warning, updates notification text (`"Foreground service timeout reached; stopping cleanly"`), cancels `syncJob` and active `activeJobs`, and calls `stopSelf(startId)`.
- **Alternatives Rejected:** Changing `foregroundServiceType` to `specialUse` / `remoteMessaging` (risks Play Store policy rejection).
- **Trade-offs:** Accepts Android 14+'s 6-hour-per-24-hour `dataSync` cap until restarted by WorkManager or the user.

---

## Verification
- Unit tests in `WasmComputeEngineTest` and `StandardShellProcessRunnerTest` covering structured cancellation, local timeout vs external cancellation, repeated cancel loops, and zero child process leaks.
- Robolectric `@Config(sdk = [28, 34])` tests in `WorkerForegroundServiceTest` covering `cancel_task_ids` dispatch (including while thermally throttled) and API 34+ `onTimeout(startId)` graceful shutdown.
