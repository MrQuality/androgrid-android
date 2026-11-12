# ADR-007: gRPC-OkHttp TLS 1.2 and 1.3 Cipher Suite Negotiation for Mobile mTLS Transport

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-14 / G-15
- **Traceability:** `[INV-002]`, `[REQ-OP-001]`, `[NFR-SEC-001]`
- **Standard:** ISO/IEC/IEEE 29148
- **GitHub Issue:** #40
- **GitHub PR:** #41

---

## Context
Under architectural invariant `[INV-002]`, the AndroGrid mobile worker client must communicate with the control plane orchestrator exclusively over mutual TLS (mTLS) with hardware-backed ECDSA P-256 key pairs.

In `androgrid-worker` `v0.0.1-alpha.2`, `MtlsSocketFactoryProvider.kt` configured an internal wrapper `MtlsTls13SocketFactory` that explicitly restricted the active SSLSocket protocols:
```kotlin
val protocols = socket.supportedProtocols.filter { it == "TLSv1.3" }
if (protocols.isNotEmpty()) {
    socket.enabledProtocols = protocols.toTypedArray()
}
```

During physical device testing on Android 15 (API 35/37, Google Pixel 8), attempting to connect either via bootstrap enrollment or via `WorkerForegroundService` (`SyncNodeState`) failed immediately. The orchestrator logged:
```text
DEBUG tonic::transport::server::incoming: accept loop error error=tls handshake eof
```

### Empirical Root Cause Analysis
1. Android uses Conscrypt as its default TLS provider.
2. The gRPC transport library for Android is `grpc-okhttp` (version 1.62+).
3. OkHttp enforces an internal `ConnectionSpec.MODERN_TLS` configuration for HTTP/2 cleartext and TLS handshakes.
4. OkHttp's `ConnectionSpec.MODERN_TLS` specifies an explicit whitelist of HTTP/2-approved cipher suites. In the bundled version of OkHttp within `grpc-okhttp`, this whitelist primarily enumerates TLS 1.2 cipher suites (such as `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` and `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`).
5. When `MtlsTls13SocketFactory` restricted enabled protocols exclusively to `TLSv1.3`, OkHttp's cipher suite filter intersected the socket's enabled cipher suites with its whitelist.
6. The resulting intersection was empty, causing Conscrypt to abort the TLS handshake before emitting a valid ClientHello.

---

## Decision
We modify `MtlsTls13SocketFactory.configureSocket()` to enable both `TLSv1.3` and `TLSv1.2` protocols:

```kotlin
val supported = socket.supportedProtocols.toSet()
val targetProtocols = listOf("TLSv1.3", "TLSv1.2").filter { supported.contains(it) }
if (targetProtocols.isNotEmpty()) {
    socket.enabledProtocols = targetProtocols.toTypedArray()
}
```

### Architectural Rationale
1. **Cryptographic Invariant Preservation (`[INV-002]`):** Both TLS 1.3 and TLS 1.2 (with ECDHE and AEAD) provide forward secrecy, authenticated encryption with associated data (AEAD), and mutual authentication with ECDSA P-256 client certificates.
2. **Library Interoperability:** Enabling `TLSv1.2` alongside `TLSv1.3` allows OkHttp's `ConnectionSpec` cipher negotiation to find a valid mutual cipher suite (`TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`) while still permitting TLS 1.3 negotiation when supported by both endpoints.
3. **No Compromise on Security:** Legacy, insecure protocols (`SSLv3`, `TLSv1.0`, `TLSv1.1`) and weak ciphers (CBC mode, RC4, 3DES) remain strictly disabled.

---

## Consequences

### Positive
- **Stable mTLS Transport:** Both bootstrap enrollment and runtime telemetry synchronization establish persistent HTTP/2 connections over mTLS without handshake termination.
- **Backward and Forward Compatibility:** Seamlessly operates across diverse Android API levels (API 28 through API 37+) and Tonic / Rustls control plane versions.

### Negative / Trade-offs
- The client does not exclusively mandate TLS 1.3 at the transport socket layer, allowing fallback to TLS 1.2 when the gRPC OkHttp layer dictates it.

---

## Verification & Traceability
- **Unit & Robolectric Testing:** Validated via `MtlsSocketFactoryProviderTest` asserting both `TLSv1.3` and `TLSv1.2` are preserved in enabled protocols.
- **Physical Hardware Verification:** Validated on Google Pixel 8 (Android 15 / API 37) with successful registration (`node_Pixel_8_2c424c62`), active telemetry heartbeats, and successful Wasmtime sandbox task execution.
