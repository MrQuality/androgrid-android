# ProGuard & R8 Optimization Rules for AndroGrid Mobile Worker Client
# Traces to: AG-SPEC-2026-V2, [REQ-VER-001], [REQ-VER-002], [NFR-PERF-001]

# -----------------------------------------------------------------------------
# Protobuf Lite & Generated Data Contracts
# -----------------------------------------------------------------------------
# Preserve reflection-free Protobuf Lite message models and builders
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Preserve generated AndroGrid protocol buffer classes
-keep class com.androgrid.contract.** { *; }
-dontwarn com.androgrid.contract.**

# -----------------------------------------------------------------------------
# gRPC Java & OkHttp Transport Layer
# -----------------------------------------------------------------------------
# Preserve gRPC transport channel builders, stub invocations, and OkHttp client
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**
-keepnames class io.grpc.** { *; }
-keepclassmembers enum io.grpc.** { *; }

# Preserve gRPC status codes and metadata accessors
-keepclassmembers class io.grpc.Status { *; }
-keepclassmembers class io.grpc.Metadata { *; }

# -----------------------------------------------------------------------------
# Chicory Embedded WebAssembly (WASI) Runtime
# -----------------------------------------------------------------------------
# Preserve pure-Java Chicory WASM bytecode interpreter and memory managers
-keep class com.dylibso.chicory.** { *; }
-dontwarn com.dylibso.chicory.**

# -----------------------------------------------------------------------------
# Jetpack WorkManager & Typed Foreground Services
# -----------------------------------------------------------------------------
# Preserve WorkManager runtime classes and Android lifecycle initializers
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Preserve AndroGrid worker service classes instantiated via reflection by WorkManager
-keep class com.androgrid.worker.service.** { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# -----------------------------------------------------------------------------
# Kotlin Coroutines & Asynchronous Flow Runtime
# -----------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
# BouncyCastle Cryptographic Service Provider & ASN.1 Parsers
# -----------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# -----------------------------------------------------------------------------
# ZXing Barcode Scanning Core
# -----------------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# -----------------------------------------------------------------------------
# CameraX & Google ML Kit Barcode Scanning
# -----------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
