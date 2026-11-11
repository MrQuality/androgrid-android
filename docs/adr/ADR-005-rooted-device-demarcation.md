# ADR-005: Rooted Device Demarcation and Toybox User Sandbox Separation

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[INV-006]`, `[REQ-OP-001]`, `[REQ-VER-002]`
- **Standard:** ISO/IEC/IEEE 29148

---

## Context
The Android edge computing landscape spans a broad spectrum of hardware configurations:
1. **Consumer & Enterprise Fleets (Unrooted, 99%+ of devices):**
   * Handsets managed by Mobile Device Management (MDM) or used by consumer owners are strictly unrooted.
   * Attempting to invoke root binaries (`su`) or executing unprivileged shell scripts triggers endpoint protection systems (Samsung Knox, Google Play Protect) and MDM compliance quarantine.
2. **Dedicated Test Benches & Developer Lab Hardware (Rooted):**
   * Internal hardware testing clusters and developer emulators may have root access enabled to measure low-level kernel counters, benchmark native toybox utilities, or execute system-level profiling.
3. **The Botnet Liability Risk:**
   * If an edge computing client unconditionally exposes root shell execution to a remote control plane, security researchers and CISOs will categorize the software as a Remote Access Trojan (RAT) or mobile botnet.

---

## Decision
We enforce strict **Rooted Device Demarcation (`[INV-006]`)** and **Engine Tiering**:
1. **Dynamic Capability Attestation:**
   * During node registration (`RegisterNode`), the mobile client actively detects root state via `RootChecker.isRooted()` and reports `NodeCapabilities.is_rooted`.
2. **Strict Demarcation on Unrooted Nodes (`is_rooted = false`):**
   * Unrooted nodes are strictly restricted to two compute engines:
     1. `EXECUTION_ENGINE_WASM_WASI` (via `WasmComputeEngine`): In-process managed WebAssembly sandbox.
     2. `EXECUTION_ENGINE_TOYBOX_USER` (via `ToyboxUserEngine`): Unprivileged shell command execution running under the unprivileged application UID inside the standard Android app sandbox.
   * If a control plane dispatches `EXECUTION_ENGINE_ROOT_SHELL` to an unrooted node, `TaskExecutionDispatcher` hard rejects the task with a security violation error before process invocation.
3. **Controlled Execution on Rooted Nodes (`is_rooted = true`):**
   * Rooted nodes can optionally execute `EXECUTION_ENGINE_ROOT_SHELL` (via `RootShellEngine`), invoking commands through `su -c`.
   * Execution requires explicit authorization and opt-in settings within the application UI.

### Architectural Rationale
* **Enterprise Security Posture:** Ensures that consumer and enterprise deployments present zero root escalation risks.
* **Separation of Concerns:** Unprivileged user shell commands (`ToyboxUserEngine`) cannot modify system partitions, read other application data, or bypass Android SELinux domain boundaries (`untrusted_app`).
* **Audit Transparency:** Every task claim and dispatch specifies its required engine, enabling automated policy enforcement at the boundary.

---

## Consequences

### Positive
* Protects unrooted devices from remote administrative exploitation.
* Enables compliant deployment within enterprise environments.
* Verified by automated test suites (`RootShellEngineTest.kt`, `ToyboxUserEngineTest.kt`, `TaskExecutionDispatcherTest.kt`).

### Negative / Trade-offs
* Rooted workloads cannot run on standard consumer hardware.
* Orchestrator must inspect node capabilities prior to scheduling root-dependent tasks.
