package com.androgrid.worker.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Validates the release automation pipeline configuration, R8/ProGuard preservation rules,
 * Gradle signing keystore configuration, and typographic integrity for Android release packaging.
 *
 * Traces to: AG-SPEC-2026-V2, [REQ-VER-001], [REQ-VER-002], [NFR-PERF-001]
 */
class ReleaseAutomationIntegrityTest {

    private fun getProjectRootDir(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    /**
     * Validates that .github/workflows/release.yml exists and triggers on version tags (v*),
     * executing release APK and AAB assembly, SHA-256 digest generation, and GitHub release upload.
     *
     * Traces to: [REQ-VER-001], [NFR-PERF-001]
     */
    @Test
    fun testReleaseWorkflowConfigurationIntegrity() {
        val rootDir = getProjectRootDir()
        val workflowFile = File(rootDir, ".github/workflows/release.yml")
        assertThat(workflowFile.exists()).isTrue()

        val content = workflowFile.readText(Charsets.UTF_8)
        assertThat(content).isNotEmpty()

        // Verify version tag trigger
        assertThat(content).contains("tags:")
        assertThat(content).contains("v*")

        // Verify Gradle assembly tasks
        assertThat(content).contains("assembleRelease")
        assertThat(content).contains("bundleRelease")

        // Verify Keystore decoding from GitHub Secrets
        assertThat(content).contains("RELEASE_KEYSTORE_BASE64")

        // Verify SHA-256 checksum consolidation
        assertThat(content).contains("SHA256SUMS.txt")

        // Verify release upload / publication
        assertThat(content).contains("gh release")
    }

    /**
     * Validates that app/build.gradle.kts defines release signing configuration with
     * fallback to debug signing when release keystore credentials are not present.
     *
     * Traces to: [REQ-VER-001]
     */
    @Test
    fun testGradleReleaseSigningConfigurationWithDebugFallback() {
        val rootDir = getProjectRootDir()
        val buildGradleFile = File(rootDir, "app/build.gradle.kts")
        assertThat(buildGradleFile.exists()).isTrue()

        val content = buildGradleFile.readText(Charsets.UTF_8)

        // Verify signingConfigs block definition
        assertThat(content).contains("signingConfigs")

        // Verify release signing block
        assertThat(content).contains("create(\"release\")")

        // Verify fallback mechanism to debug signing
        assertThat(content).contains("debug")

        // Verify release build type references signing configuration
        assertThat(content).contains("signingConfig")

        // Verify ProGuard rules configuration is enabled for release
        assertThat(content).contains("proguard-rules.pro")
    }

    /**
     * Validates that app/proguard-rules.pro exists and explicitly preserves required
     * reflection-free Protobuf Lite models, gRPC OkHttp transport, Chicory WASM runtime,
     * and Jetpack WorkManager worker classes.
     *
     * Traces to: [REQ-VER-002], [NFR-PERF-001]
     */
    @Test
    fun testProguardRulesPreserveCriticalDependencies() {
        val rootDir = getProjectRootDir()
        val proguardFile = File(rootDir, "app/proguard-rules.pro")
        assertThat(proguardFile.exists()).isTrue()

        val content = proguardFile.readText(Charsets.UTF_8)
        assertThat(content).isNotEmpty()

        // Verify Protobuf Lite preservation
        assertThat(content).contains("com.google.protobuf")

        // Verify gRPC transport preservation
        assertThat(content).contains("io.grpc")

        // Verify Chicory WASM runtime preservation
        assertThat(content).contains("com.dylibso.chicory")

        // Verify Jetpack WorkManager and Worker preservation
        assertThat(content).contains("androidx.work")
        assertThat(content).contains("com.androgrid.worker.service")
    }

    /**
     * Asserts zero occurrences of forbidden Unicode em dashes (U+2014) and en dashes (U+2013)
     * in the release workflow and ProGuard configuration files.
     *
     * Traces to: AGENTS.md Typographic Standards
     */
    @Test
    fun testZeroUnicodeDashesInReleaseArtifacts() {
        val rootDir = getProjectRootDir()
        val targetFiles = listOf(
            File(rootDir, ".github/workflows/release.yml"),
            File(rootDir, "app/proguard-rules.pro"),
            File(rootDir, "app/build.gradle.kts")
        )

        for (file in targetFiles) {
            if (file.exists()) {
                val content = file.readText(Charsets.UTF_8)
                assertThat(content).doesNotContain("\u2014")
                assertThat(content).doesNotContain("\u2013")
            }
        }
    }
}
