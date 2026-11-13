# ADR-008: Task Execution Ledger and Lifecycle-Aware Observability Persistence

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-13 / G-14 / G-15
- **Traceability:** `[REQ-OP-002]`, `[REQ-OP-003]`, `[REQ-VER-001]`, `[REQ-VER-002]`, `[INV-004]`, `[NFR-POW-001]`
- **Standard:** ISO/IEC/IEEE 29148
- **GitHub Issue:** #43
- **GitHub PR:** (Associated PR)

---

## Context

During physical end-to-end hardware testing of `androgrid-worker` `v0.0.1-alpha.2` on physical Google Pixel 8 hardware (TR-ALPHA2-PIXEL8-E2E-001), compute tasks (`hello_wasi.wasm` and `image-processor.wasm`) executed successfully within the sandbox, completing with exit code 0. However, the worker dashboard UI displayed `Tasks Executed: 0` throughout the entire session.

A comprehensive architectural and empirical audit revealed three underlying deficiencies across the service, state management, and presentation layers:

### 1. Disconnected Execution Telemetry
While `TaskExecutionDispatcher` measures wall-clock duration and executes tasks via `ComputeEngine` implementations (`WasmComputeEngine`, `ToyboxUserEngine`, `RootShellEngine`), neither execution service bridges completion telemetry back to the dashboard:
- `WorkerForegroundService.kt` finalizes `TaskExecutionReport` entries and places them into an outbound queue for `SyncNodeStateRequest` without recording local telemetry.
- `CellularSpotWorker.kt` claims unary tasks via `ClaimCellularTask` and submits results via `SubmitCellularResult` without updating local state.
- `WorkerDashboardViewModel.incrementCompletedTasks()` had zero callers in `src/main/`, leaving `tasksCompletedCount` hardcoded at 0 in production.

### 2. Broken ViewModel Scoping & Activity Lifecycle Amnesia
In `MainActivity.kt`, `WorkerDashboardViewModel` was instantiated directly via its constructor inside `onCreate()`:
```kotlin
viewModel = WorkerDashboardViewModel(...)
```
Because the ViewModel was not scoped through `ViewModelProvider.Factory`, any configuration change (such as device rotation) or navigating to `SettingsActivity` and returning destroyed and recreated `MainActivity`, resetting `tasksCompletedCount` to 0. Furthermore, state observation used un-guarded `lifecycleScope.launch { viewModel.uiState.collect { ... } }`, continuously collecting flow emissions and executing UI layout passes even when the Activity was stopped or backgrounded.

### 3. Concurrency Races in UI State
`WorkerDashboardViewModel.kt` mutated state using non-atomic assignments (`_uiState.value = _uiState.value.copy(...)`). Introducing a concurrent task completion stream alongside `routeFlow` and `thermalStatusFlow` created read-modify-write race conditions where concurrent emissions clobbered state updates.

---

## Architectural Trade-off Analysis

### Alternative 1: Android Jetpack Room Database
- *Pros:* Structured relational tables, SQLite support, built-in Flow observation.
- *Cons:* `app/build.gradle.kts` currently does not include Room, KSP, KAPT, or Hilt. Adding Room pulls in heavy annotation processors, runtime libraries, and schema migration infrastructure for an edge compute worker whose core mission is minimal APK footprint and high computational efficiency. Rejected as architectural bloat.

### Alternative 2: Hot In-Memory Kotlin SharedFlow
- *Pros:* Trivial to implement, zero disk I/O.
- *Cons:* A hot `SharedFlow` with `replay = 0` drops emissions whenever `MainActivity` is paused, backgrounded, or killed while background services continue executing. Re-subscribing upon rotation risks double-counting, and cumulative state is wiped completely upon process restart. Rejected as unreliable.

### Alternative 3: Jetpack Compose + Material 3 UI Migration
- *Pros:* Modern declarative UI, declarative LazyColumn lists.
- *Cons:* The worker app currently uses programmatic Android Views with zero external UI framework dependencies. Introducing Jetpack Compose BOM, Material 3, foundation, and the Kotlin Compose compiler plugin adds 5+ MB of library bloat to the APK, slows CI compilation times, and increases runtime memory initialization latency on low-end edge hardware (2 GB RAM). Rejected as scope creep.

---

## Decision

We implement a dedicated, persistent, and lifecycle-safe **Task Execution Ledger** architecture using established Android primitives:

### 1. App-Scoped `TaskLedgerRepository` (Single Source of Truth)
We define an application-scoped singleton interface `TaskLedgerRepository` and implementation `SharedPreferencesTaskLedgerRepository` mirroring the proven pattern of `SharedPreferencesClusterSettingsRepository`:
- **State Model:** Exposes `val ledgerSnapshot: StateFlow<TaskLedgerSnapshot>` containing `tasksCompletedCount`, `tasksFailedCount`, and a bounded ring-buffer (last 20 entries) of `TaskLedgerEntry`.
- **Durability:** Persists snapshot state asynchronously on `Dispatchers.IO` using Android `SharedPreferences`. Counts and recent task history survive screen rotation, activity destruction, and background process termination.
- **Idempotent Deduplication (`[INV-004]`):** Enforces deduplication on `(taskId, runAttempt)`. Re-evaluations or speculative retries under invariant `[INV-004]` do not artificially inflate cumulative counters.

### 2. Dual-Route Completion Hooks
Both background execution pathways record results into `TaskLedgerRepository`:
- **Wi-Fi Sync Loop (`WorkerForegroundService`):** Invokes `ledgerRepository.recordTaskExecution(report)` upon finalizing a `TaskExecutionReport`.
- **Cellular Spot Execution (`CellularSpotWorker`):** Invokes `ledgerRepository.recordTaskExecution(result, taskId, runAttempt)` upon completing `TaskExecutionDispatcher.dispatch()`.
- **Success vs. Failure Demarcation:** Only `exitCode == 0` (or `TaskState == TASK_STATE_COMPLETED`) increments `tasksCompletedCount`. Tasks terminating with non-zero exits, timeouts, or runtime exceptions increment `tasksFailedCount` and are flagged with status `FAILED` in the recent ledger.

### 3. Atomic State Concurrency in ViewModel
`WorkerDashboardViewModel` replaces all direct `_uiState.value = _uiState.value.copy(...)` calls with CAS-based `_uiState.update { it.copy(...) }`. The ViewModel binds to `ledgerRepository.ledgerSnapshot`, reactively updating `tasksCompletedCount`, `tasksFailedCount`, and the recent task ledger safely under high concurrency.

### 4. Lifecycle-Aware Scoping in MainActivity
- `MainActivity` instantiates `WorkerDashboardViewModel` through a custom `ViewModelProvider.Factory` (`WorkerDashboardViewModelFactory`), ensuring the ViewModel instance survives orientation changes and activity restarts.
- Flow collection is wrapped within `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }`, suspending UI rendering when the Activity is stopped and resuming automatically when visible, conserving battery power (`[NFR-POW-001]`).

---

## Consequences

### Positive
- **Accurate Real-Time Observability:** Operators receive immediate, live feedback on the dashboard as tasks complete across both Wi-Fi and cellular routes.
- **Resilience Across Lifecycles:** Cumulative execution counts and recent task history persist across configuration changes, backgrounding, and process death.
- **Hermetic Architecture:** `WasmComputeEngine` and other `ComputeEngine` implementations remain stateless and decoupled from telemetry and UI concerns.
- **Lean APK & Zero New Dependencies:** Solves state persistence and presentation using existing Android Jetpack primitives without inflating binary size or build times.

### Negative / Trade-offs
- **Ring Buffer Ceiling:** In-memory and SharedPreferences persistence bounds the recent task ledger to 20 entries. Historical audit logs beyond 20 entries require external logcat collection.

### Neutral
- `MainActivity` retains its lightweight programmatic View layout, extending existing metric displays rather than rewriting the UI framework.

---

## Verification & Acceptance Criteria
1. **Repository Hermeticity:** `SharedPreferencesTaskLedgerRepositoryTest` validates deduplication of `(taskId, runAttempt)`, accurate incrementing of completed vs. failed counts, and asynchronous persistence.
2. **ViewModel Concurrency:** `WorkerDashboardViewModelTest` validates concurrent emissions from `ledgerSnapshot`, `routeFlow`, and `thermalStatusFlow` without dropped updates.
3. **Activity Recreation:** `MainActivityTest` under Robolectric verifies that `recreate()` retains task counters without resetting to 0.
4. **Documentation Integrity:** `DocumentationIntegrityTest` validates presence and structure of `ADR-008-task-execution-ledger-and-lifecycle-persistence.md`.
