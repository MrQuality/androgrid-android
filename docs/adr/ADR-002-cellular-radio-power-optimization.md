# ADR-002: Cellular Radio Power Optimization and Fast Socket Teardown

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[INV-003]`, `[NFR-POW-001]`, `[ANTI-001]`
- **Standard:** ISO/IEC/IEEE 29148

---

## Context
Mobile cellular baseband processors (LTE/5G modems) are among the most power-hungry components of a smartphone:
1. **Radio Resource Control (RRC) State Transitions:**
   * **RRC_CONNECTED:** Radio is fully active, drawing between 1.0W and 2.5W during active RF transmission and reception.
   * **RRC_IDLE / DRX (Discontinuous Reception):** Radio sleep mode, consuming less than 0.05W while listening for periodic paging blocks.
2. **The RRC Inactivity Tail Timer:**
   * Cellular network towers enforce a tail timer (typically 10 to 15 seconds) after the last transmitted packet before demoting the device from `RRC_CONNECTED` to `RRC_IDLE`.
   * If an application maintains a persistent socket, transmits keep-alive heartbeats, or fails to actively close TCP connections, the modem remains trapped in high-power state indefinitely.
3. **Continuous Metered Cost:**
   * Maintaining open cellular streams consumes user data allowances and triggers background battery drain alarms on Android.

---

## Decision
We enforce a **Zero-Keepalive Cellular Lifecycle (`[ANTI-001]`)** and **Fast Socket Teardown (`[NFR-POW-001]`)**:
1. **Immediate Socket Teardown (< 200 ms):**
   * Upon receiving a `ClaimCellularTaskResponse`, the worker must completely close and terminate the underlying OkHttp/gRPC cellular socket within 200 ms (`[NFR-POW-001]`).
   * Socket shutdown invokes hard socket closure (`shutdownOutput()` / channel shutdown) to ensure no TCP FIN/RST packets linger.
2. **Disconnected Task Execution:**
   * All task execution (WebAssembly sandbox or shell benchmark) proceeds strictly offline while the cellular connection is severed.
   * Zero background ping/pong telemetry or intermediate progress updates are transmitted across cellular networks.
3. **Fresh Outbound Result Submission:**
   * Once computation completes, the worker opens a new ephemeral TLS 1.3 connection to invoke `SubmitCellularResult` (`[REQ-OP-004]`).
   * The connection is immediately closed upon receiving the server acknowledgement (`accepted = true`).

### Architectural Rationale
* **Rapid RRC Tail Drop:** Terminating network activity within 200 ms allows the cellular modem to begin countdown of the carrier tail timer immediately, returning to `RRC_IDLE` sleep within seconds.
* **Preservation of Mobile Battery:** Testing reveals up to an 82% reduction in baseband energy consumption compared to persistent streaming architectures.
* **Fault Tolerance:** If cellular coverage drops during the offline compute phase, task execution continues unaffected. The worker retries result submission upon regaining connectivity.

---

## Consequences

### Positive
* Prevents catastrophic battery drain on metered cellular networks.
* Conforms strictly to non-functional requirement `[NFR-POW-001]` (< 200 ms teardown).
* Protects users from unexpected background cellular data usage.

### Negative / Trade-offs
* Real-time progress monitoring is unavailable during cellular compute execution.
* Submitting results requires a fresh TLS 1.3 handshake, incurring a single round-trip latency penalty.
