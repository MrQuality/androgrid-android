package com.androgrid.worker.documentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Validates documentation completeness, link integrity, parameter breakdown depth,
 * and typographic compliance for the Android worker documentation.
 *
 * Traces to: AG-SPEC-2026-V2, [REQ-OP-001], [REQ-OP-002], [REQ-OP-003], [REQ-VER-001], [INV-001], [INV-002], [INV-003], [NFR-POW-001]
 */
class DocumentationIntegrityTest {

    private fun getProjectDocsDir(): File {
        // Resolve project root docs directory
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "docs").exists() && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return File(dir, "docs")
    }

    @Test
    fun testWorkerOnboardingGuideCompletenessAndParameterDepth() {
        val docsDir = getProjectDocsDir()
        val guideFile = File(docsDir, "worker-guide.md")
        assertThat(guideFile.exists()).isTrue()

        val content = guideFile.readText(Charsets.UTF_8)
        assertThat(content).isNotEmpty()

        // Verify Android version requirements
        assertThat(content).contains("Android 9")
        assertThat(content).contains("API 28")

        // Verify ADB commands and explicit argument explanations
        assertThat(content).contains("adb install")
        assertThat(content).contains("-r")
        assertThat(content).contains("-g")

        // Verify OpenSSL PKI commands and argument breakdowns
        assertThat(content).contains("openssl")
        assertThat(content).contains("prime256v1")
        assertThat(content).contains("-export")
        assertThat(content).contains("client.p12")

        // Verify OEM background task killing workarounds
        assertThat(content).contains("Samsung")
        assertThat(content).contains("Xiaomi")
        assertThat(content).contains("Huawei")

        // Verify Podman local cluster connection instructions
        assertThat(content).contains("Podman")
    }

    @Test
    fun testArchitectureDocumentationCompletenessAndTraceability() {
        val docsDir = getProjectDocsDir()
        val archFile = File(docsDir, "architecture.md")
        assertThat(archFile.exists()).isTrue()

        val content = archFile.readText(Charsets.UTF_8)
        assertThat(content).isNotEmpty()

        // Section 6.1 State Matrix
        assertThat(content).contains("Section 6.1")
        assertThat(content).contains("Connection State")

        // WorkManager spot scheduling vs persistent sync
        assertThat(content).contains("WorkManager")
        assertThat(content).contains("SyncNodeState")
        assertThat(content).contains("ClaimCellularTask")

        // Sandboxed Compute Engine hierarchy
        assertThat(content).contains("WASM")
        assertThat(content).contains("Toybox")

        // Traceability Tags
        val requiredTags = listOf(
            "[REQ-OP-001]",
            "[REQ-OP-002]",
            "[REQ-OP-003]",
            "[REQ-VER-001]",
            "[INV-001]",
            "[INV-002]",
            "[INV-003]",
            "[NFR-POW-001]"
        )
        for (tag in requiredTags) {
            assertThat(content).contains(tag)
        }
    }

    @Test
    fun testZeroForbiddenUnicodeDashesInDocumentation() {
        val docsDir = getProjectDocsDir()
        if (!docsDir.exists()) return

        val files = docsDir.walkTopDown().filter { it.isFile && (it.extension == "md" || it.extension == "html") }
        for (file in files) {
            val lines = file.readLines(Charsets.UTF_8)
            for ((idx, line) in lines.withIndex()) {
                assertWithMessage("Forbidden em dash (U+2014) in ${file.name}:${idx + 1}")
                    .that(line.contains('\u2014'))
                    .isFalse()
                assertWithMessage("Forbidden en dash (U+2013) in ${file.name}:${idx + 1}")
                    .that(line.contains('\u2013'))
                    .isFalse()
            }
        }
    }

    @Test
    fun testGitHubPagesWorkflowIntegrity() {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, ".github").exists()) {
            dir = dir.parentFile
        }
        val pagesFile = File(dir, ".github/workflows/pages.yml")
        assertThat(pagesFile.exists()).isTrue()

        val content = pagesFile.readText(Charsets.UTF_8)
        assertThat(content).contains("dokkaHtml")
        assertThat(content).contains("deploy-pages")
        assertThat(content).contains("assemble_pages.py")
    }

    @Test
    fun testClientArchitectureDecisionRecordsCompleteness() {
        val docsDir = getProjectDocsDir()
        val adrDir = File(docsDir, "adr")
        assertThat(adrDir.exists()).isTrue()
        assertThat(adrDir.isDirectory).isTrue()

        val expectedAdrs = listOf(
            "ADR-001-zero-inbound-ports.md",
            "ADR-002-cellular-radio-power-optimization.md",
            "ADR-003-typed-foreground-service-and-workmanager.md",
            "ADR-004-in-memory-wasm-wasi-execution.md",
            "ADR-005-rooted-device-demarcation.md"
        )

        for (adrFileName in expectedAdrs) {
            val adrFile = File(adrDir, adrFileName)
            assertWithMessage("Missing required client ADR: $adrFileName").that(adrFile.exists()).isTrue()

            val content = adrFile.readText(Charsets.UTF_8)
            assertThat(content).contains("## Status")
            assertThat(content).contains("Accepted")
            assertThat(content).contains("## Context")
            assertThat(content).contains("## Decision")
            assertThat(content).contains("## Consequences")
            assertThat(content).contains("ISO/IEC/IEEE 29148")
        }
    }

    @Test
    fun testIso29148ClientTraceabilityMatrixCompleteness() {
        val docsDir = getProjectDocsDir()
        val matrixFile = File(docsDir, "traceability_matrix.md")
        assertThat(matrixFile.exists()).isTrue()

        val content = matrixFile.readText(Charsets.UTF_8)
        assertThat(content).contains("ISO/IEC/IEEE 29148")

        val requiredRequirementTags = listOf(
            "[REQ-OP-001]",
            "[REQ-OP-002]",
            "[REQ-OP-003]",
            "[REQ-OP-004]",
            "[INV-001]",
            "[INV-002]",
            "[INV-003]",
            "[INV-006]",
            "[ANTI-001]",
            "[ANTI-003]",
            "[NFR-SEC-001]",
            "[NFR-POW-001]",
            "[NFR-PERF-001]",
            "[REQ-VER-001]",
            "[REQ-VER-002]",
            "[REQ-VER-003]"
        )

        for (tag in requiredRequirementTags) {
            assertWithMessage("Traceability matrix must cite requirement $tag").that(content).contains(tag)
        }
    }
}
