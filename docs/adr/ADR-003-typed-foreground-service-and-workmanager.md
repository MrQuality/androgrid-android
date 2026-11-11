# ADR-003: Typed Foreground Service (API 34+) and WorkManager Hybrid Background Lifecycle

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[REQ-VER-001]`, `[REQ-OP-002]`, `[REQ-OP-003]`
- **Standard:** ISO/IEC/IEEE 29148

---

## Context
Background execution on modern Android operating systems is subject to aggressive process lifecycle management:
1. **Android 14+ (API 34) Typed Foreground Service Enforcement:**
   * Android 14 mandates that every foreground service declare an explicit type in `AndroidManifest.xml` and pass that type to `ServiceCompat.startForeground()`.
   * Calling `startForeground()` without a matching type or with an invalid type throws a fatal `SecurityException`.
2. **Aggressive OEM Task Killing:**
   * Handset manufacturers (Samsung, Xiaomi, Huawei, OnePlus) implement aggressive low-memory killers (LMK) and proprietary battery managers that terminate background services when the application leaves the user foreground.
3. **Workload Heterogeneity:**
   * AndroGrid executes both continuous high-bandwidth sync workloads (when connected to unmetered Wi-Fi) and opportunistic spot claims (when idle on cellular).
   * A single service architecture cannot satisfy both operating requirements.

---

## Decision
We implement a **Hybrid Lifecycle Architecture** combining Android Jetpack **WorkManager** and **Typed Foreground Services**:
1. **Typed Foreground Service (`WorkerForegroundService`):**
   * Declares `android:foregroundServiceType="dataSync"` in `AndroidManifest.xml`.
   * Invokes `ServiceCompat.startForeground()` passing `FOREGROUND_SERVICE_TYPE_DATA_SYNC` strictly complying with `[REQ-VER-001]`.
   * Displays an ongoing status notification showing active compute task metrics, memory usage, and battery temperature.
   * Manages the continuous 2.0-second `SyncNodeState` loop while the application is active or actively charging on Wi-Fi.
2. **Opportunistic Spot Execution (`CellularSpotWorker`):**
   * Implemented as an Android Jetpack `CoroutineWorker`.
   * Configured with explicit battery and network constraints:
     * `setRequiredNetworkType(NetworkType.CONNECTED)`
     * `setRequiresBatteryNotLow(true)`
   * Executes episodic `ClaimCellularTask` cycles, launches the task locally, and schedules result delivery through WorkManager's guaranteed delivery queue.
3. **Thermal Safety Guard Interlocking:**
   * Both lifecycle components listen to `ThermalSafetyGuard`. If battery temperature exceeds safety thresholds (45°C), compute tasks are deferred or throttled.

### Architectural Rationale
* **Platform Policy Compliance:** Fulfills Android 14 (API 34) and Android 15 (API 35) foreground service regulations, preventing runtime crashes.
* **Resilience Against OEM Task Killing:** Using WorkManager provides guaranteed task scheduling that survives process death, device reboots, and OEM power manager sweeps.
* **User Visibility:** The mandatory persistent notification gives users full transparency and manual control over edge compute resource consumption.

---

## Consequences

### Positive
* Zero `SecurityException` crashes on Android 14+.
* High execution reliability on OEM devices notorious for aggressive background process termination.
* Fully tested via Robolectric and Android automation tests (`WorkerForegroundServiceTest.kt`, `CellularSpotWorkerTest.kt`).

### Negative / Trade-offs
* WorkManager introduces job scheduling jitter (execution timing is subject to OS optimization).
* The persistent notification is mandatory and cannot be hidden by the user without disabling the service.
