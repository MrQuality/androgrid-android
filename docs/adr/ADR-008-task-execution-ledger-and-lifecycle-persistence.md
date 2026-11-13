# ADR-008: Task Execution Ledger and Lifecycle-Aware Observability Persistence

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-13 / G-14 / G-15
- **Traceability:** `[REQ-OP-002]`, `[REQ-OP-003]`, `[REQ-VER-001]`, `[REQ-VER-002]`, `[INV-004]`, `[NFR-POW-001]`, `[NFR-SEC-001]`
- **Standard:** ISO/IEC/IEEE 29148
- **GitHub Issue:** #43
- **GitHub PR:** #46

---

## Context

During physical end-to-end hardware testing of `androgrid-worker` `v0.0.1-alpha.2` on physical Google Pixel 8 hardware (`TR-ALPHA2-PIXEL8-E2E-001`), compute tasks (`hello_wasi.wasm` and `image-processor.wasm`) executed successfully within the sandbox, completing with exit code 0. However, the worker dashboard UI displayed `Tasks Executed: 0` throughout the entire session.

A comprehensive architectural and empirical audit revealed three underlying deficiencies across the service, state management, and presentation layers:

### 1. Disconnected Execution Telemetry & Missing In-Flight Guard
While `TaskExecutionDispatcher` measures wall-clock duration and executes tasks via `ComputeEngine` implementations (`WasmComputeEngine`, `ToyboxUserEngine`, `RootShellEngine`), neither execution service bridges completion telemetry back to the dashboard:
- `WorkerForegroundService.kt` finalizes `TaskExecutionReport` entries and places them into an outbound queue for `SyncNodeStateRequest` without recording local telemetry. Furthermore, it dispatches `desiredTasksList` on every 2.0-second sync tick without tracking in-flight `(taskId, runAttempt)` pairs, risking redundant execution if a task exceeds `SYNC_INTERVAL_MS`.
- `CellularSpotWorker.kt` claims unary tasks via `ClaimCellularTask` and submits results via `SubmitCellularResult` without updating local state.
- `WorkerDashboardViewModel.incrementCompletedTasks()` had zero callers in `src/main/`, leaving `tasksCompletedCount` hardcoded at 0 in production.
- `WorkerForegroundService.kt` checked `result.exitCode == 0`, diverging from the canonical `TaskExecutionResult.isSuccess` (`exitCode == 0 && errorCode.isEmpty()`).

### 2. Broken ViewModel & Monitor Lifecycle Scoping
In `MainActivity.kt`, `WorkerDashboardViewModel` was instantiated directly via its constructor inside `onCreate()`, and `MainActivity.onDestroy()` called `networkMonitor.stopMonitoring()` and `thermalListener?.stopListening()`. Moreover, `WorkerDashboardViewModel.startParticipation()` bound `WorkerLifecycleCoordinator` to `viewModelScope`.
- Rotating the device or navigating to `SettingsActivity` and back destroyed `MainActivity`, resetting `tasksCompletedCount` to 0 and killing the underlying network/thermal monitors.
- Backing out of `MainActivity` cancelled `viewModelScope`, severing `WorkerLifecycleCoordinator` route transitions while `WorkerForegroundService` remained active.
- State observation used un-guarded `lifecycleScope.launch { viewModel.uiState.collect { ... } }` without `repeatOnLifecycle(Lifecycle.State.STARTED)`.

### 3. Concurrency Races in UI State
`WorkerDashboardViewModel.kt` mutated state using non-atomic assignments (`_uiState.value = _uiState.value.copy(...)`). Introducing a concurrent task completion stream alongside `routeFlow` and `thermalStatusFlow` creates read-modify-write race conditions where concurrent emissions clobber state updates.

---

## Architectural Trade-off Analysis

### Alternative 1: Android Jetpack Room Database
- *Pros:* Structured relational tables, SQLite support, built-in Flow observation.
- *Cons:* `app/build.gradle.kts` currently does not include Room, KSP, KAPT, or Hilt. Adding Room pulls in heavy annotation processors, runtime libraries, and schema migration infrastructure for an edge compute worker whose core mission is minimal APK footprint and high computational efficiency. Rejected as architectural bloat.

### Alternative 2: Hot In-Memory Kotlin SharedFlow
- *Pros:* Trivial to implement, zero disk I/O.
- *Cons:* A hot `SharedFlow` with `replay = 0` drops emissions whenever `MainActivity` is paused, backgrounded, or killed while background services continue executing. Re-subscribing upon rotation risks double-counting, and cumulative state is wiped completely upon process restart. Rejected as unreliable.

### Alternative 3: Jetpack Compose + Material 3 UI Migration
- *Pros:* Modern declarative UI, declarative `LazyColumn` lists.
- *Cons:* The worker app currently uses programmatic Android Views with zero external UI framework dependencies. Introducing Jetpack Compose BOM, Material 3, foundation, and the Kotlin Compose compiler plugin adds 5+ MB of library bloat to the APK, slows CI compilation times, and increases runtime memory initialization latency on low-end edge hardware (2 GB RAM). Rejected as scope creep.

### Alternative 4: Protobuf Base64 vs. Versioned `org.json` in `SharedPreferences`
- *Decision:* `org.json` (`"version": 1`) is built into the Android SDK, avoids coupling local UI ledger storage to `androgrid-backend` `.proto` wire schemas, requires no Gson/Moshi/kotlinx-serialization dependencies, and remains human-inspectable during diagnostics.

---

## Decision

We implement an `Application`-scoped, persistent, and lifecycle-safe **Task Execution Ledger** architecture using established Android primitives:

### 1. `Application`-Scoped Lifecycle Container (`AndroGridApplication`)
An `AndroGridApplication` class (registered in `AndroidManifest.xml`) hosts an application-scoped container with a `SupervisorJob` coroutine scope:
- **Lazy Initialization & Zero Main-Thread Disk I/O:** Container components initialize via `by lazy`; `Application.onCreate()` performs zero disk I/O and starts zero listeners (avoiding StrictMode `DiskReadViolation` on cold start and Robolectric tests).
- **Demand-Gated Monitors (`[NFR-POW-001]`):** Owns `NetworkRouteMonitor` and `AndroidDeviceThermalStatusListener`, gated so listeners run ONLY while `isComputeActive.value == true` OR at least one Activity is in the `STARTED` state (ref-counted via `onStart`/`onStop`). When idle in the background, monitors are stopped to preserve battery.
- **Unified Participation Controller:** Owns `WorkerLifecycleCoordinator` and `isComputeActive: StateFlow<Boolean>`. `isComputeActive` is persisted via `SharedPreferences` (hydrated off-main-thread and committed on toggle), and `WorkerForegroundService` reconciles with `(applicationContext as? AndroGridApplication)?.participationController?.syncActiveFromServiceStart()` on `ACTION_START_SYNC` (including `START_STICKY` null-intent restarts) so UI state, monitors, and coordinator never diverge after LMK restarts. Both `MainActivity` (via `WorkerDashboardViewModel`) and `TestAutomationReceiver` delegate to this single `Application`-scoped controller.
- **Safe Container Resolution:** Services, workers, and receivers accept optional dependency injection and resolve defaults via safe `as? AndroGridApplication` casts with repository singleton fallback, preventing `ClassCastException` in unit tests with mocked `Context` instances.
- `MainActivity.onDestroy()` does NOT stop monitors or cancel `WorkerLifecycleCoordinator`.

### 2. Durable, Serialized `TaskLedgerRepository` (Single Source of Truth)
We define `TaskLedgerRepository` and `SharedPreferencesTaskLedgerRepository` mirroring `SharedPreferencesClusterSettingsRepository`:
- **Async Off-Main-Thread Hydration & `ensureHydratedLocked()`:** Initializes `MutableStateFlow(TaskLedgerSnapshot.EMPTY)` immediately on the Main Thread without reading `SharedPreferences`, then hydrates asynchronously via `ioDispatcher`. Both `recordTaskExecution()` and `isRecorded()` invoke `ensureHydratedLocked()` inside `mutex.withLock` on `withContext(ioDispatcher)`, preventing cold-start writes from overwriting persisted counts from `0` (`RSK-HYD-01`).
- **Synchronization & Durability:** `suspend fun recordTaskExecution(...)` acquires a `kotlinx.coroutines.sync.Mutex` inside `withContext(ioDispatcher)` (default `Dispatchers.IO`) and persists via synchronous `editor.commit()` rather than `apply()`. This guarantees serialized writes and prevents silent data loss if Android LMK or `stopSelf()` kills the process before `QueuedWork` flushes.
- **Versioned Serialization & Fault Recovery:** Serializes state as versioned `org.json` (`"version": 1`). Deserialization is wrapped in `try / catch (JSONException)` with schema-version validation; corrupt or incompatible payloads reset cleanly to an empty `TaskLedgerSnapshot` and overwrite on the next commit.
- **Separated UI Ring Buffer & Deterministic FIFO Dedup Window (`[INV-004]`):**
  - **UI Recent-Tasks Ring Buffer:** Bounded to the last **20** `TaskLedgerEntry` items.
  - **Deduplication FIFO Window:** Backed by an insertion-ordered `LinkedHashSet<String>` serialized as a `JSONArray` in oldest-to-newest order, bounded to **512** `"$taskId:$runAttempt"` keys (~20-30 KB in XML). Only terminal statuses (`COMPLETED`, `FAILED`, `STALE`) are inserted into `seenKeys`; non-terminal `SUBMISSION_FAILED` is excluded so subsequent terminal executions are never suppressed (`RSK-DEDUP-02`). At capacity, `iterator().next()` is evicted deterministically before insertion.
  - **Dedup Query Method:** Exposes `suspend fun isRecorded(taskId: String, runAttempt: Int): Boolean` (acquiring the `Mutex` and awaiting `ensureHydratedLocked()`) so callers check the full 512-key index safely.

### 3. Dual-Route Execution Semantics & Data Hygiene
- **Unified Success Predicate:** Both `WorkerForegroundService` and `CellularSpotWorker` standardize on `result.isSuccess` (`exitCode == 0 && errorCode.isEmpty()`).
- **In-Flight & Cancellation Guard in `WorkerForegroundService`:**
  - Tracks active `(taskId, runAttempt)` keys in `ConcurrentHashMap.newKeySet()`, removing in a `finally` block and skipping tasks already in-flight or already recorded via `ledgerRepository.isRecorded(...)` so tasks exceeding 2.0s are never re-dispatched redundantly (`[NFR-POW-001]`).
  - Explicitly `catch (e: CancellationException) { throw e }` around task dispatch so stopping sync (`ACTION_STOP_SYNC` or `collectLatest`) never maps cancellation to `FAILED`. Enqueues `completedReports.add(report)` **before** `withContext(NonCancellable) { ledgerRepository.recordTaskExecution(...) }` so outbound `SyncNodeStateRequest` reporting is never delayed behind disk `commit()`.
- **Cellular Submission Outcomes (`CellularSpotWorker`):** Records to `TaskLedgerRepository` **after** `SubmitCellularResult` resolves (since WorkManager retries re-invoke `claimTask()` rather than re-submitting cached results):
  - `COMPLETED`: `result.isSuccess` and `SubmitCellularResult` succeeded. Increments `tasksCompletedCount`.
  - `FAILED`: `!result.isSuccess`. Increments `tasksFailedCount`.
  - `STALE`: `result.isSuccess` locally, but orchestrator rejected with `TASK_ATTEMPT_STALE`. Recorded in the recent ledger as `STALE` and **does NOT increment `tasksCompletedCount`**.
  - `SUBMISSION_FAILED`: `result.isSuccess` locally, but `SubmitCellularResult` failed with a transient network error before returning `Result.retry()`. Recorded in the recent ledger as `SUBMISSION_FAILED` so the executed task is not silently lost, and **does NOT increment `tasksCompletedCount` or `tasksFailedCount`**.
- **Strict Data Hygiene (`[NFR-SEC-001]`):** `TaskLedgerEntry` contains strictly `taskId`, `runAttempt`, `route` (`"WIFI_LAN"` / `"CELLULAR_WAN"`), `status` (`COMPLETED`, `FAILED`, `STALE`, `SUBMISSION_FAILED`), `exitCode`, `errorCode`, `executionDurationMs`, and `completedAtEpochMs`. Raw `stdout` and `stderr` are **strictly excluded** from `SharedPreferences`.

### 4. ViewModel Concurrency & Lifecycle-Safe Activity Collection
- `WorkerDashboardViewModel` replaces all `_uiState.value = _uiState.value.copy(...)` mutations with CAS-based `_uiState.update { it.copy(...) }`.
- `MainActivity` instantiates `WorkerDashboardViewModel` via a `ViewModelProvider.Factory`.
- `app/build.gradle.kts` explicitly declares `androidx.lifecycle:lifecycle-runtime-ktx:2.8.0`, and `MainActivity` wraps state collection in `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }`.

### 5. Test Isolation
- Provide `InMemoryTaskLedgerRepository` fake for hermetic unit tests.
- Provide `@VisibleForTesting internal fun resetForTesting()` on `SharedPreferencesTaskLedgerRepository.Companion` to prevent cross-test state leakage in Robolectric suites.

---

## Consequences

### Positive
- **Accurate Real-Time Observability:** Operators receive immediate, live feedback on the dashboard across both Wi-Fi and cellular routes, including `STALE` preemption visibility.
- **Resilience Across Lifecycles:** Cumulative execution counts, `isComputeActive` state, monitors, and recent task history survive configuration changes, back-navigation, and process death.
- **No Wasted Retries or Split-Brain Telemetry:** In-flight tracking, 512-key dedup, and post-submission recording keep client counts synchronized with the orchestrator.
- **Lean APK & Zero New Heavy Dependencies:** Solves state persistence and lifecycle scoping using existing Android Jetpack primitives without Room or Compose bloat.

### Negative / Trade-offs
- **Ring Buffer Ceiling:** UI history is capped at 20 recent entries and deduplication at 512 attempts. Historical audit logs beyond these windows require external logcat or orchestrator telemetry.

### Neutral
- `MainActivity` retains its lightweight programmatic View layout, extending existing metric displays rather than rewriting the UI framework.

---

## Verification & Acceptance Criteria
1. **Repository Hermeticity & Durability:** `SharedPreferencesTaskLedgerRepositoryTest` validates `Mutex` serialization, 512-key deduplication of `(taskId, runAttempt)`, 20-entry ring buffer eviction, corrupt JSON recovery, and `stdout`/`stderr` exclusion.
2. **ViewModel Concurrency:** `WorkerDashboardViewModelTest` validates concurrent emissions from `ledgerSnapshot`, `routeFlow`, `thermalStatusFlow`, and `isComputeActive` without dropped updates.
3. **Dual-Route Integration:** Tests for `WorkerForegroundService` (in-flight guard + completion) and `CellularSpotWorker` (`COMPLETED`, `FAILED`, `STALE`, and transient `Result.retry()`).
4. **Activity Recreation:** `MainActivityTest` under Robolectric verifies `recreate()` retains `tasksCompletedCount`, `isComputeActive`, and active route/thermal streams without resets.
5. **Documentation Integrity:** `DocumentationIntegrityTest` validates presence and structure of `ADR-008-task-execution-ledger-and-lifecycle-persistence.md`.
