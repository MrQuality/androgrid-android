# ISO/IEC/IEEE 29148 Requirement Traceability Matrix (Android Client)

**Specification Baseline:** AG-SPEC-2026-V2 (`AndroGrid_SDD.md`)  
**Standard Compliance:** ISO/IEC/IEEE 29148:2018 (Systems and software engineering - Life cycle processes - Requirements engineering)  
**Verification Baseline:** Automated Kotlin Unit Tests, Robolectric Tests, Jetpack WorkManager TestHelper, Coroutines TestScope  
**Typographic Standard:** Zero Unicode em dashes (U+2014) or en dashes (U+2013). Strict ASCII hyphen (-) conformance.

---

## 1. Executive Summary & Architecture Governance

This Traceability Matrix establishes bidirectional verification traceability between the normative requirements defined in `AG-SPEC-2026-V2` and the production codebase of the AndroGrid Android Worker Client (`androgrid-android`).

In accordance with ISO/IEC/IEEE 29148:2018:
1. **Forward Traceability:** Every functional requirement (`[REQ-...]`), architectural invariant (`[INV-...]`), proscribed anti-requirement (`[ANTI-...]`), and non-functional quality attribute (`[NFR-...]`) traces forward to concrete Kotlin source components, services, and engines.
2. **Backward Traceability:** Every test suite, Robolectric test, and formal KDoc docstring links backward to its governing master requirement identifier.
3. **Formal Consensus:** Architecture Decision Records (`docs/adr/`) document the architectural rationale, rejected alternatives, and operational trade-offs for all foundational Android client design choices.

---

## 2. Requirements Traceability Matrix

| Requirement ID | Requirement Title & Normative Scope | Master Spec Ref | Android Client Source Implementation | Verification Test Suites | Client ADR |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `[REQ-OP-001]` | **Node Registration (`RegisterNode`)**<br>Generates hardware-backed ECDSA keypair, requests mTLS certificate, reports system capabilities within 500 ms. | Section 4.1<br>Section 5.1 | `enrollment/AndroidKeyStoreEnrollmentProvider.kt`<br>`enrollment/EnrollmentCoordinator.kt`<br>`capability/DeviceCapabilityProvider.kt` | `enrollment/EnrollmentCoordinatorTest.kt`<br>`enrollment/KeyEnrollmentProviderTest.kt`<br>`capability/DeviceCapabilityProviderTest.kt` | `docs/adr/ADR-001-zero-inbound-ports.md`<br>`docs/adr/ADR-005-rooted-device-demarcation.md` |
| `[REQ-OP-002]` | **Declarative Wi-Fi State Sync (`SyncNodeState`)**<br>2.0-second reconciliation loop transmitting active task execution states and receiving new task assignments. | Section 4.1<br>Section 5.1<br>Section 6.1 | `network/WifiSyncClient.kt`<br>`service/WorkerForegroundService.kt`<br>`service/WorkerLifecycleCoordinator.kt` | `network/WifiSyncClientTest.kt`<br>`service/WorkerForegroundServiceTest.kt`<br>`service/WorkerLifecycleCoordinatorTest.kt` | `docs/adr/ADR-003-typed-foreground-service-and-workmanager.md` |
| `[REQ-OP-003]` | **Disconnected Cellular Task Claim (`ClaimCellularTask`)**<br>Unary task lease claim over cellular, closing socket within 200 ms and executing offline without persistent keepalive. | Section 4.1<br>Section 5.1<br>Section 6.2 | `network/CellularTransportClient.kt`<br>`service/CellularSpotWorker.kt`<br>`network/NetworkRouteMonitor.kt` | `network/CellularRadioLifecycleTest.kt`<br>`service/CellularSpotWorkerTest.kt`<br>`network/NetworkRouteMonitorTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md`<br>`docs/adr/ADR-003-typed-foreground-service-and-workmanager.md` |
| `[REQ-OP-004]` | **Atomic Result Submission (`SubmitCellularResult`)**<br>Establishes new outbound TLS 1.3 connection to deliver completed task artifacts with attempt fencing token. | Section 4.1<br>Section 5.1<br>Section 6.2 | `network/CellularTransportClient.kt`<br>`service/CellularSpotWorker.kt`<br>`engine/TaskExecutionResult.kt` | `network/CellularRadioLifecycleTest.kt`<br>`service/CellularSpotWorkerTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md` |
| `[INV-001]` | **Zero Inbound Mobile Ports**<br>Worker nodes must never open listening TCP or UDP sockets. All communications originate strictly outbound toward control plane. | Section 3.1<br>Section 2.2 | `network/CellularTransportClient.kt`<br>`network/WifiSyncClient.kt`<br>`service/WorkerForegroundService.kt` | `network/InboundPortGuardTest.kt`<br>`service/WorkerForegroundServiceTest.kt` | `docs/adr/ADR-001-zero-inbound-ports.md` |
| `[INV-002]` | **Mutual TLS 1.3 Enforcement**<br>All gRPC and HTTP interactions strictly enforce TLS 1.3 with ECDSA P-256 client certificates verified against cluster root CA. | Section 3.1<br>Section 7 | `network/MtlsSocketFactoryProvider.kt`<br>`enrollment/AndroidKeyStoreEnrollmentProvider.kt`<br>`network/ClusterSettingsRepository.kt` | `network/MtlsSocketFactoryProviderTest.kt`<br>`enrollment/KeyEnrollmentProviderTest.kt`<br>`network/ClusterSettingsRepositoryTest.kt` | `docs/adr/ADR-001-zero-inbound-ports.md` |
| `[INV-003]` | **Disconnected Execution & Power Bounds**<br>Execution occurs strictly in disconnected state on cellular. Radio socket closes immediately without keep-alive ping frames. | Section 3.1<br>Section 7 | `network/CellularTransportClient.kt`<br>`service/CellularSpotWorker.kt`<br>`telemetry/ThermalSafetyGuard.kt` | `network/CellularRadioLifecycleTest.kt`<br>`telemetry/ThermalSafetyGuardTest.kt`<br>`telemetry/BatteryThermalMonitorTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md` |
| `[INV-006]` | **Sandbox Isolation Boundary**<br>Unrooted worker nodes constrained strictly to in-process WASI or unprivileged user toybox execution. Refuses root shell payloads. | Section 3.1<br>Section 4.1 | `engine/TaskExecutionDispatcher.kt`<br>`engine/WasmComputeEngine.kt`<br>`engine/ToyboxUserEngine.kt`<br>`engine/RootShellEngine.kt` | `engine/TaskExecutionDispatcherTest.kt`<br>`engine/WasmComputeEngineTest.kt`<br>`engine/ToyboxUserEngineTest.kt`<br>`engine/RootShellEngineTest.kt` | `docs/adr/ADR-004-in-memory-wasm-wasi-execution.md`<br>`docs/adr/ADR-005-rooted-device-demarcation.md` |
| `[ANTI-001]` | **Proscribed Cellular Keepalive**<br>Client shall not maintain persistent socket connections, ping/pong telemetry, or idle streaming over metered cellular networks. | Section 3.2 | `network/CellularTransportClient.kt`<br>`service/CellularSpotWorker.kt` | `network/CellularRadioLifecycleTest.kt`<br>`documentation/DocumentationIntegrityTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md` |
| `[ANTI-003]` | **Proscribed Unmetered/Long Payloads on Cellular**<br>Client drops or rejects tasks marked requires_unmetered when operating across metered cellular routes. | Section 3.2<br>Section 5.1 | `service/CellularSpotWorker.kt`<br>`network/NetworkRouteMonitor.kt` | `service/CellularSpotWorkerTest.kt`<br>`network/NetworkRouteMonitorTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md` |
| `[NFR-SEC-001]` | **Cryptographic Transport Security**<br>TLS 1.3 forward-secret AEAD cipher suites, hardware KeyStore backing, and ephemeral token authentication. | Section 7 | `network/MtlsSocketFactoryProvider.kt`<br>`enrollment/AndroidKeyStoreEnrollmentProvider.kt` | `network/MtlsSocketFactoryProviderTest.kt`<br>`enrollment/KeyEnrollmentProviderTest.kt` | `docs/adr/ADR-001-zero-inbound-ports.md` |
| `[NFR-POW-001]` | **Baseband Radio Efficiency**<br>Socket termination within 200 ms of ClaimCellularTaskResponse receipt enabling fast baseband RRC tail drop into low-power sleep. | Section 7<br>Section 3.1 | `network/CellularTransportClient.kt`<br>`service/CellularSpotWorker.kt` | `network/CellularRadioLifecycleTest.kt` | `docs/adr/ADR-002-cellular-radio-power-optimization.md` |
| `[NFR-PERF-001]` | **Message Frame Bounds (16 MB)**<br>Upper bound of 16 MB strictly enforced on gRPC buffers and in-memory WebAssembly bytecode payloads. | Section 7<br>Section 2.3 | `engine/WasmComputeEngine.kt`<br>`contract/ContractConstants.kt` | `engine/WasmComputeEngineTest.kt`<br>`contract/ContractWireTest.kt` | `docs/adr/ADR-004-in-memory-wasm-wasi-execution.md` |
| `[REQ-VER-001]` | **Typed Foreground Service (API 34+)**<br>Worker client passes FOREGROUND_SERVICE_TYPE_DATA_SYNC to startForeground on Android 14+ devices. | Appendix A.2<br>Appendix A.3 | `service/WorkerForegroundService.kt`<br>`AndroidManifest.xml` | `service/WorkerForegroundServiceTest.kt` | `docs/adr/ADR-003-typed-foreground-service-and-workmanager.md` |
| `[REQ-VER-002]` | **Dynamic Capability Reporting**<br>Worker supplies exact Build.VERSION.RELEASE, primary ABI, and root status in NodeCapabilities. | Appendix A.3<br>Section 4.1 | `capability/DeviceCapabilityProvider.kt`<br>`capability/RootChecker.kt` | `capability/DeviceCapabilityProviderTest.kt` | `docs/adr/ADR-005-rooted-device-demarcation.md` |
| `[REQ-VER-003]` | **W^X Native Sandbox Compatibility**<br>Embedded WASI runtime executes entirely in memory without writing or executing shared objects on writable storage. | Appendix A.2<br>Appendix A.3 | `engine/WasmComputeEngine.kt` | `engine/WasmComputeEngineTest.kt` | `docs/adr/ADR-004-in-memory-wasm-wasi-execution.md` |

---

## 3. Architecture Decision Records Directory

The detailed architectural trade-offs, context, and operational consequences for the Android worker client are documented in:
* [`ADR-001: Zero Inbound Listening Ports and Outbound-Only Mobile Networking`](adr/ADR-001-zero-inbound-ports.md)
* [`ADR-002: Cellular Radio Power Optimization and Fast Socket Teardown`](adr/ADR-002-cellular-radio-power-optimization.md)
* [`ADR-003: Typed Foreground Service (API 34+) and WorkManager Hybrid Background Lifecycle`](adr/ADR-003-typed-foreground-service-and-workmanager.md)
* [`ADR-004: In-Memory WebAssembly/WASI Execution without Dynamic Shared Library Disk Emission`](adr/ADR-004-in-memory-wasm-wasi-execution.md)
* [`ADR-005: Rooted Device Demarcation and Toybox User Sandbox Separation`](adr/ADR-005-rooted-device-demarcation.md)
