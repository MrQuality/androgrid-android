import com.google.protobuf.gradle.id

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
    id("org.jetbrains.dokka")
}

android {
    namespace = "com.androgrid.worker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androgrid.worker"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-alpha.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: project.findProperty("RELEASE_KEYSTORE_PATH") as? String
            val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String

            if (!keystorePath.isNullOrBlank() && File(keystorePath).exists()) {
                storeFile = File(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // Fallback to debug keystore for development forks and local test builds
                val debugSigning = getByName("debug")
                storeFile = debugSigning.storeFile
                storePassword = debugSigning.storePassword
                this.keyAlias = debugSigning.keyAlias
                this.keyPassword = debugSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Desugaring support for Android minSdk 28
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Embedded WebAssembly (WASI) Runtime - Chicory
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasi:1.7.5")

    // Jetpack WorkManager & Foreground Services
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")

    // gRPC & Protobuf Lite
    implementation("io.grpc:grpc-okhttp:1.64.0")
    implementation("io.grpc:grpc-protobuf-lite:1.64.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")
    implementation("com.google.protobuf:protobuf-kotlin-lite:3.25.1")

    // Cryptography & PKCS#10 CSR Generation
    // Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // CameraX & Barcode Scanning (QR Code Scanner)
    // Traces to: [REQ-OP-001], [NFR-SEC-001]
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Unit & Robolectric Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.grpc:grpc-testing:1.64.0")
    testImplementation("io.grpc:grpc-inprocess:1.64.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.64.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
            task.plugins {
                id("grpc") {
                    option("lite")
                }
                id("grpckt") {
                    option("lite")
                }
            }
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
