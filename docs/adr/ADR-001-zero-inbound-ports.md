# ADR-001: Zero Inbound Listening Ports and Outbound-Only Mobile Networking

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[INV-001]`, `[NFR-SEC-001]`, `[REQ-OP-001]`
- **Standard:** ISO/IEC/IEEE 29148

---

## Context
Mobile devices participating as distributed edge compute workers operate across hostile, dynamic network environments:
1. **Carrier-Grade NAT (CGNAT):** Mobile network operators (MNOs) assign private IPv4 addresses (RFC 6598 `100.64.0.0/10`) and dynamic IPv6 prefixes to cellular handsets. Inbound connections from external networks cannot traverse carrier firewalls without specialized APNs.
2. **Network Address Transitions:** Handsets frequently transition between Wi-Fi access points and cellular base stations, changing local IP addresses and invalidating active transport connections.
3. **Attack Surface & Device Security:** Exposing an open TCP or UDP listening socket on a mobile handset exposes the device to unauthorized network scans, potential buffer overflow attacks, and unsolicited packet processing that drains device battery while the handset is idle.

---

## Decision
We enforce **Zero Inbound Listening Ports (`[INV-001]`)** across the entire Android mobile worker architecture:
1. **Outbound-Only Transport:** All network traffic originates strictly outbound from the mobile worker toward the central control plane (either directly over LAN Wi-Fi or via Cloudflare Anycast gRPC tunnel ingress).
2. **No Server Sockets:** The Android client application shall never bind or listen on `ServerSocket`, `DatagramSocket`, or embedded HTTP/gRPC servers.
3. **Polling & Task Claim Mechanism:** Task discovery operates via outbound client polling:
   * Over Wi-Fi: High-frequency declarative sync loop (`SyncNodeState`) initiated by the worker every 2.0 seconds (`[REQ-OP-002]`).
   * Over Cellular: Periodic on-demand task lease claims (`ClaimCellularTask`) initiated via WorkManager (`[REQ-OP-003]`).

### Architectural Rationale
* **Universal NAT Traversal:** By relying exclusively on outbound TCP/TLS connections, mobile workers function seamlessly behind arbitrary NAT gateways, enterprise firewalls, and carrier CGNAT without port-forwarding configuration (`[ANTI-002]`).
* **Zero Attack Surface:** With zero listening ports open on the handset, remote network scans cannot detect or probe the AndroGrid worker process.
* **Preservation of Mobile Sleep States:** The device modem remains dormant until the application initiates an outbound connection, preserving battery life and conforming to mobile operating system power management guidelines.

---

## Consequences

### Positive
* Seamless connectivity across Wi-Fi, 4G, 5G, and public hotspots without network administrator intervention.
* Eliminates network vulnerability vectors targeting listening ports on mobile devices.
* Verified by automated tests (`InboundPortGuardTest.kt`).

### Negative / Trade-offs
* Task dispatch latency is bounded by the polling/sync interval (up to 2.0 seconds on Wi-Fi).
* Real-time push notifications from orchestrator to worker are not natively supported; synchronization must be initiated by the handset.
