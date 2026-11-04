# AndroGrid Android Worker Node

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android%2014%2B%20(API%2034)-green.svg)](https://developer.android.com)
[![MinSDK](https://img.shields.io/badge/Min%20SDK-API%2028-yellow.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org)
[![gRPC](https://img.shields.io/badge/gRPC-Kotlin%201.4-blue.svg)](https://github.com/grpc/grpc-kotlin)
[![WASI](https://img.shields.io/badge/Engine-Wasmtime%20%2F%20WASI-red.svg)](https://wasmtime.dev)

The distributed mobile edge compute worker for **AndroGrid**: executing isolated WebAssembly (WASI) modules and privileged shell tasks on Android devices orchestrated by a central Rust control plane over mutual-TLS 1.3.

---

## Key Capabilities

- **Zero Inbound Mobile Ports (`[INV-001]`):** The worker node never binds to, listens on, or exposes server sockets. All cluster communication originates outbound from the mobile device to the control plane.
- **Zero Cellular Keepalive and Radio Sleep (`[INV-003]`, `[NFR-POW-001]`):** When operating over metered Cellular WAN, the worker actively terminates underlying gRPC HTTP/2 channels within 200 ms of task receipt, enabling the cellular baseband radio to drop into low-power RRC idle sleep during local execution.
- **Android 14+ Typed Foreground Services (`[REQ-VER-001]`):** Implements modern foreground service architectures using `FOREGROUND_SERVICE_TYPE_DATA_SYNC` with backward compatibility down to Android 9 (API 28).
- **In-Memory WebAssembly Sandbox (`[INV-006]`, `[REQ-VER-003]`):** Unrooted devices execute compute payloads strictly inside an embedded WASI runtime via JNI. Bytecode is evaluated in memory via `memfd_create` and anonymous pages, respecting strict Android SELinux W^X memory policies.
- **Dual-Route Interface Monitoring:** Dynamically transitions between high-throughput Wi-Fi declarative state loops and ephemeral cellular unary requests.

---

## Technical Stack

- **Target OS:** Android 14+ (API Level 34)
- **Minimum OS:** Android 9.0 (API Level 28)
- **Language and Concurrency:** Kotlin 2.0+, Coroutines, Kotlin Flow
- **Background Orchestration:** Jetpack WorkManager 2.9+, AndroidX ServiceCompat
- **RPC and Transport:** `grpc-kotlin-stub`, `grpc-protobuf-lite`, OkHttp Transport with TLS 1.3
- **Compute Sandboxes:** Android NDK r26+, Embedded Wasmtime C-API via JNI, Toybox shell engine

---

## Getting Started

### Prerequisites
- **Android Studio:** Hedgehog (2023.1.1) or newer
- **Java Development Kit:** OpenJDK 17 or 21
- **Android NDK:** r26+ (`26.1.10909125`)
- **Android SDK:** Platform API 34, Build Tools 34.0.0

### Building the Application
```bash
# Clone the repository
git clone https://github.com/MrQuality/androgrid-android.git
cd androgrid-android

# Assemble debug APK
./gradlew assembleDebug

# Run unit and Robolectric tests
./gradlew testDebugUnitTest
```

---

## Project Structure

```text
androgrid-android/
├── app/
│   ├── src/main/
│   │   ├── proto/            # Canonical Protocol Buffer contracts
│   │   ├── kotlin/           # Worker services, network watchers, and UI
│   │   │   └── com/androgrid/
│   │   │       ├── service/  # Typed Foreground Services & WorkManager
│   │   │       ├── network/  # Dual-route transport & TLS 1.3 channel
│   │   │       └── engine/   # WASI sandbox & native execution bridge
│   │   ├── cpp/              # NDK JNI bridge to embedded WASI runtime
│   │   └── AndroidManifest.xml
│   └── src/test/             # Robolectric & Coroutines test suites
├── gradle/                   # Gradle wrapper
└── build.gradle.kts          # Top-level build configuration
```

---

## Community and Governance

We welcome contributions to AndroGrid! Please review our community guidelines:

- [CONTRIBUTING.md](CONTRIBUTING.md): Contribution workflow, testing requirements, and branch standards.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md): Contributor Covenant v2.1 standards.
- [SECURITY.md](SECURITY.md): Responsible disclosure policy for security vulnerabilities.

---

## License

This project is licensed under the [MIT License](LICENSE).
