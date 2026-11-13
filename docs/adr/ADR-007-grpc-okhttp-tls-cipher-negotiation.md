# ADR-007: Strict TLS 1.3 Channel Configuration and RFC 5280 Trust Pinning Validation

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

In `androgrid-worker` `v0.0.1-alpha.2`, `MtlsSocketFactoryProvider.kt` configured an internal wrapper `MtlsTls13SocketFactory` that restricted active SSLSocket protocols:
```kotlin
val protocols = socket.supportedProtocols.filter { it == "TLSv1.3" }
if (protocols.isNotEmpty()) {
    socket.enabledProtocols = protocols.toTypedArray()
}
```

During physical device testing on Android 15 (API 35/37, Google Pixel 8), connecting either via bootstrap enrollment or via `WorkerForegroundService` failed with:
```text
DEBUG tonic::transport::server::incoming: accept loop error error=tls handshake eof
```

An initial hotfix attempted to resolve this by permitting `TLSv1.2` in `MtlsTls13SocketFactory`. However, a comprehensive code review by external advisors revealed that this violated `[INV-002]` and failed to address the true root cause inside `grpc-okhttp`.

### Empirical Root Cause Analysis
1. In `io.grpc:grpc-okhttp:1.64.0`, `OkHttpChannelBuilder` initializes its default `connectionSpec` to `INTERNAL_DEFAULT_CONNECTION_SPEC`.
2. Disassembly of `OkHttpChannelBuilder` reveals that `INTERNAL_DEFAULT_CONNECTION_SPEC` hardcodes `tlsVersions = [TlsVersion.TLS_1_2]`.
3. During connection establishment, `OkHttpTlsUpgrader.upgrade()` calls `connectionSpec.apply(sslSocket, false)` **after** `sslSocketFactory.createSocket()` returns.
4. `ConnectionSpec.apply()` invokes `sslSocket.setEnabledProtocols(...)` and `sslSocket.setEnabledCipherSuites(...)`, **completely clobbering** whatever settings `MtlsTls13SocketFactory.configureSocket()` applied during socket creation.
5. When `MtlsTls13SocketFactory` only enabled `TLSv1.3`, `ConnectionSpec.apply()` intersected `{TLSv1.3}` with `{TLSv1.2}`, yielding an empty set and causing Conscrypt to abort the TLS handshake.
6. Furthermore, `createPinningTrustManager` checked signature chains but omitted RFC 5280 `basicConstraints >= 0` checks on intermediate certificates, creating a potential leaf-as-CA forgeability risk.
7. `WifiSyncClient` and `CellularTransportClient` silently fell back to Android's system trust store with unauthenticated channels if mTLS credentials were null.

### Rationale for Dual Enforcement (tlsConnectionSpec + MtlsTls13SocketFactory)
While `tlsConnectionSpec` configures OkHttp's connection-level protocol filter, `MtlsTls13SocketFactory` remains essential as defense-in-depth: `grpc-okhttp` internally chooses between `INTERNAL_DEFAULT_CONNECTION_SPEC` (which includes TLS 1.3 only when `AndroidNegotiator` is loaded at runtime on a real Android platform) and `INTERNAL_LEGACY_CONNECTION_SPEC` (which defaults to TLS 1.2 on Robolectric or host JVM). Explicitly configuring both `tlsConnectionSpec` on `OkHttpChannelBuilder` and `MtlsTls13SocketFactory` on the underlying `SSLSocketFactory` guarantees hermetic TLS 1.3 enforcement across real device runtimes and host unit test environments without relying on undocumented library defaults.

---

## Decision
We enforce strict TLS 1.3 channel configuration and robust certificate path verification across all mobile client network layers:

1. **Direct `OkHttpChannelBuilder.tlsConnectionSpec` Configuration:**
   Across `WifiSyncClient`, `CellularTransportClient`, and `BootstrapEnrollmentClient`, we configure the channel builder directly with:
   ```kotlin
   builder
       .useTransportSecurity()
       .sslSocketFactory(socketFactory)
       .tlsConnectionSpec(
           MtlsContext.TLS_13_VERSIONS, // ["TLSv1.3"]
           MtlsContext.TLS_13_CIPHERS   // ["TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"]
       )
   ```
   This overrides `OkHttpChannelBuilder`'s default `connectionSpec`, ensuring that `OkHttpTlsUpgrader` enforces pure TLS 1.3.

2. **Strict Socket Factory Guard:**
   `MtlsTls13SocketFactory.configureSocket()` strictly enforces `"TLSv1.3"`. Any fallback to `TLSv1.2` is removed, maintaining uncompromising compliance with `[INV-002]`.

3. **Fail-Fast Credential Guard (Zero Silent Fallback):**
   `WifiSyncClient` and `CellularTransportClient` require a non-null `SSLSocketFactory` when constructing real gRPC channels. If credentials are missing, channel construction immediately throws `IllegalStateException`, completely eliminating unauthenticated connections to public CAs.

4. **RFC 5280 Intermediate CA Validation in Pinning Trust Manager:**
   `createPinningTrustManager` asserts that all intermediate signing certificates between the leaf and the pinned Root CA anchor have `basicConstraints >= 0` (authorized CA) and KeyUsage `keyCertSign` asserted, preventing rogue leaf certificates from being accepted as intermediate CAs.

5. **Hardware KeyStore Alias Isolation:**
   `AndroidKeyStoreEnrollmentProvider` wraps `X509ExtendedKeyManager` to strictly select the target `alias`, preventing multi-alias key confusion.

6. **Reactive Service Lifecycle:**
   `WorkerForegroundService` observes `ClusterSettingsRepository.settingsFlow` using `collectLatest`, automatically adapting to newly enrolled credentials without requiring a service restart.

---

## Consequences

### Positive
- **Strict Invariant Compliance (`[INV-002]`):** Mobile network channels strictly negotiate TLS 1.3 with AEAD cipher suites without protocol downgrade.
- **Robust Path Validation:** Custom pinning trust manager enforces RFC 5280 Section 4.2.1.9 CA authorization rules on intermediate certificates.
- **Zero Unauthenticated Fallback:** No operational channel can ever silently connect via Android's default public Web PKI CA store.
- **Reactive UX:** Mobile worker immediately begins sync loop upon QR pairing or credential updates.

### Negative / Trade-offs
- Devices or proxies that do not support TLS 1.3 cannot connect to the cluster. (This is an intended architectural invariant per `[INV-002]`).

---

## Verification & Traceability
- **Unit & Robolectric Tests:**
  - `MtlsSocketFactoryProviderTest`: Asserts `socket.enabledProtocols` contains strictly `"TLSv1.3"` and excludes `"TLSv1.2"`.
  - `BootstrapEnrollmentClientTest`: Verifies acceptance of valid pinned Root CA chains and asserts rejection of leaf certificates presented as intermediate CAs.
  - `WifiSyncClientTest`: Asserts fail-fast rejection when building real channels without `SSLSocketFactory`.
- **Physical Hardware Verification:** Physical Pixel 8 hardware verified successfully under mTLS 1.3.
