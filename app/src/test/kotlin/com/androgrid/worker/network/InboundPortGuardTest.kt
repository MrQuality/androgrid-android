package com.androgrid.worker.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Architectural invariant verification test for [INV-001].
 * Enforces zero inbound listening mobile ports across worker packages.
 *
 * Traces to: [INV-001]
 */
class InboundPortGuardTest {

    @Test
    fun testZeroInboundPorts_noServerSocketBindingsDeclared() {
        // Enforces [INV-001]: Worker node must never create or bind ServerSocket
        val workerSourceDir = File("src/main/kotlin/com/androgrid/worker")
        if (!workerSourceDir.exists()) {
            // Test run from root project directory
            val altDir = File("app/src/main/kotlin/com/androgrid/worker")
            if (altDir.exists()) {
                scanDirForServerSockets(altDir)
                return
            }
        } else {
            scanDirForServerSockets(workerSourceDir)
            return
        }

        // If directory cannot be resolved via relative path, check client classes directly
        val forbiddenTypes = listOf(
            "java.net.ServerSocket",
            "io.grpc.Server",
            "io.grpc.ServerBuilder",
            "io.grpc.netty.NettyServerBuilder"
        )
        for (typeName in forbiddenTypes) {
            val isPresentInClientCore = try {
                val clazz = Class.forName("com.androgrid.worker.network.CellularTransportClient")
                clazz.declaredFields.any { it.type.name == typeName }
            } catch (e: ClassNotFoundException) {
                false
            }
            assertThat(isPresentInClientCore).isFalse()
        }
    }

    private fun scanDirForServerSockets(dir: File) {
        val forbiddenTokens = listOf(
            "ServerSocket",
            "ServerBuilder.forPort",
            "NettyServerBuilder",
            "bind("
        )

        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                for (token in forbiddenTokens) {
                    val violation = content.contains(token) && !content.contains("scanDirForServerSockets")
                    assertWithMessage("File ${file.name} contains forbidden listening socket token: $token")
                        .that(violation)
                        .isFalse()
                }
            }
    }
}
