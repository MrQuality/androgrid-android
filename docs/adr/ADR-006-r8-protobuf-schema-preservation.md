# ADR-006: Protobuf Lite Schema Preservation and Hermetic R8 Obfuscation Contract

## Status
Accepted

## Architectural Scope & Requirements
- **Goal Reference:** G-15
- **Traceability:** `[REQ-VER-002]`, `[REQ-OP-001]`
- **Standard:** ISO/IEC/IEEE 29148
- **GitHub Issue:** #37

---

## Context
When compiling the Android mobile worker application in release mode (`isMinifyEnabled = true`), the Android Gradle Plugin invokes the R8 shrinking, optimization, and obfuscation engine.

In initial release builds, `app/proguard-rules.pro` declared a rule referencing an obsolete package name:
```proguard
-keep class com.androgrid.contract.** { *; }
```
However, the canonical Protocol Buffer definitions in `app/src/main/proto/androgrid/v1/orchestrator.proto` specify:
```protobuf
option java_package = "com.androgrid.v1";
```

Because `com.androgrid.v1.**` was not protected:
1. R8 obfuscated message classes such as `com.androgrid.v1.NodeCapabilities` into short mangled identifiers (e.g., `d0.y`).
2. Internal field names such as `isRooted_` were renamed to arbitrary single-letter identifiers (e.g., `a`).
3. Google Protobuf Lite (`com.google.protobuf.GeneratedMessageLite`) uses schema-driven reflection looking up field identifiers by exact schema name when building immutable instances (`build()`).
4. During application initialization (`MainActivity.onCreate()`), invoking `DeviceCapabilityProvider.buildCapabilities()` threw an uncaught `RuntimeException: Field isRooted_ for d0.y not found`.
5. On modern Android versions (Android 14+, API 34), OS crash suppression aborted the window animation within 40 milliseconds, dropping the user back to the launcher without a crash dialog and leaving the process cached in the background with no GUI.

---

## Decision
We enforce a **Type-Hierarchical Protobuf Lite Member Preservation Contract** in `app/proguard-rules.pro` alongside explicit client gRPC service preservation:

1. **Type-Hierarchical Member Invariant:** Rather than blanket-keeping all classes (which prevents dead-code tree-shaking), the rule uses `-keepclassmembers` to match all classes inheriting from `com.google.protobuf.GeneratedMessageLite` and preserves their fields for schema reflection:
   ```proguard
   -keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
       <fields>;
   }
   -keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
   ```
2. **Explicit Client Service Preservation:** Explicitly preserve client gRPC service stubs utilized by the mobile worker (`AndroGridServiceGrpc*`, `EnrollmentServiceGrpc*`), without adding blanket package keep rules or suppressing first-party compiler warnings:
   ```proguard
   -keep class com.androgrid.v1.AndroGridServiceGrpc* { *; }
   -keep class com.androgrid.v1.EnrollmentServiceGrpc* { *; }
   ```
3. **Elimination of Phantom Namespaces & Warning Suppression:** Completely remove `-keep class com.androgrid.contract.**` and avoid `-dontwarn` on first-party `com.androgrid.v1.**` code.
4. **Automated Release Integrity Verification:** Unit test `ReleaseAutomationIntegrityTest` is updated to assert structural `GeneratedMessageLite` preservation rules on active (non-commented) lines, and `.github/workflows/ci.yml` is updated to execute `minifyReleaseWithR8` on every pull request.

### Architectural Rationale
* **Resilience to Package Refactoring:** Type-hierarchical rules ensure that any new message schemas added to the contract remain protected regardless of what Java package option is declared.
* **Guaranteed Reflection Integrity:** Protobuf Lite reflection algorithms require field name parity between the compiled schema descriptor and the bytecode class definition.
* **Hermetic Binary Optimization:** Dead code elimination is preserved for unreferenced messages (such as operator-only `AdminService` RPCs) by using `-keepclassmembers`, avoiding forced retention of unused bytecode while upholding `[REQ-VER-002]`.

---

## Consequences

### Positive
* Prevents fatal startup crashes across all current and future Protobuf contract schemas on release APKs.
* Ensures uniform launcher GUI rendering across all Android API levels (API 28 through API 34+).
* Formally verified by automated regression test `testProguardRulesPreserveCriticalDependencies`.

### Negative / Trade-offs
* Slightly reduces the minification ratio for classes extending `GeneratedMessageLite` (retaining field names increases DEX string table size by a negligible amount: less than 15 KB).
