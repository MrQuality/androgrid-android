# ADR-004: In-Memory WebAssembly/WASI Execution without Dynamic Shared Library Disk Emission

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[REQ-VER-003]`, `[INV-006]`, `[NFR-PERF-001]`
- **Standard:** ISO/IEC/IEEE 29148

---

## Context
Executing dynamic untrusted binary workloads on Android poses severe security and platform compatibility obstacles:
1. **SELinux W^X Memory Enforcement:**
   * Modern Android platforms enforce strict Write XOR Execute (`W^X`) policies.
   * Android application sandboxes (starting in API 29) prohibit calling `dlopen()` or `execve()` on files written to writable application storage (`/data/data/com.androgrid.worker/cache` or `/files`). Any attempt to compile native code, emit a `.so` shared object to disk, and load it dynamically triggers an SELinux `execmem` violation and process abort.
2. **Architecture Fragmentation:**
   * Android devices run diverse CPU architectures: `arm64-v8a` (64-bit ARM), `armeabi-v7a` (32-bit ARM), `x86_64` (emulators/Chromebooks), and `riscv64` (emerging). Distributing native compiled binaries requires maintaining architecture-specific builds.
3. **Sandbox Escape Risks:**
   * Loading native shared libraries directly executes machine code within the process context, bypassing memory safety guarantees and enabling full process compromise if an exploit is present.

---

## Decision
We enforce **In-Memory WebAssembly/WASI Execution (`[REQ-VER-003]`)** powered by **Chicory**:
1. **Pure JVM / In-Memory Interpretation:**
   * The compute engine uses Chicory, a pure-JVM WebAssembly interpreter and WASI runtime.
   * WebAssembly bytecode is parsed, validated, and executed entirely within Java heap memory.
   * No dynamic shared libraries (`.so`), ELF binaries, or executable files are ever written to the physical filesystem.
2. **Bytecode Integrity & Boundary Bounds:**
   * Incoming WASM bytecode is validated against the task specification's SHA-256 digest (`artifact_sha256`) before parsing.
   * The bytecode frame limit is strictly bounded to 16 MB (`[NFR-PERF-001]`). Payload sizes exceeding 16 MB are rejected at the boundary.
3. **WASI Preview 1 Emulation:**
   * Provides standard WASI host imports (stdin, stdout, stderr, clock, environment variables, random source) mediated entirely through memory buffers.
   * Tasks read input buffers via stdin and write transformed output to stdout.

### Architectural Rationale
* **100% W^X Compliance:** Because execution occurs within the managed JVM bytecode interpreter, zero native executable memory pages are allocated, completely eliminating SELinux `execmem` denials.
* **Universal Portability:** A single WebAssembly module (`wasm32-wasip1`) executes identically across all Android CPU architectures without re-compilation.
* **Hermetic Sandboxing:** Even if a WASM workload contains malicious memory access attempts, it cannot escape the memory boundary of the WebAssembly linear memory buffer.

---

## Consequences

### Positive
* Operates seamlessly across all unrooted Android devices from Android 9 (API 28) to Android 15 (API 35).
* Zero risk of native code injection or SELinux security violations.
* Rigorously verified in `WasmComputeEngineTest.kt`.

### Negative / Trade-offs
* Pure-JVM WASM interpretation executes at lower raw throughput compared to native ahead-of-time (AOT) machine code compilation.
* Memory allocations are constrained by JVM heap limits (typically 256 MB to 512 MB per Android process).
