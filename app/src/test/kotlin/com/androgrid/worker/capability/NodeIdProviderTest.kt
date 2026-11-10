package com.androgrid.worker.capability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying persistent unique node identifier creation without Build.SERIAL dependencies.
 *
 * Traces to: [REQ-OP-001], [REQ-VER-001]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeIdProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear preferences before each test
        context.getSharedPreferences("androgrid_node_identity", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun getOrCreateNodeId_createsValidPrefixedIdentifier() {
        val nodeId = NodeIdProvider.getOrCreateNodeId(context)

        assertThat(nodeId).isNotNull()
        assertThat(nodeId).startsWith("node_")
        assertThat(nodeId.length).isAtLeast(8)
    }

    @Test
    fun getOrCreateNodeId_persistsAcrossInvocations() {
        val firstId = NodeIdProvider.getOrCreateNodeId(context)
        val secondId = NodeIdProvider.getOrCreateNodeId(context)

        assertThat(firstId).isEqualTo(secondId)
    }

    @Test
    fun getFallbackNodeId_returnsValidPrefixedIdentifier() {
        val fallback = NodeIdProvider.getFallbackNodeId()

        assertThat(fallback).startsWith("node_")
        assertThat(fallback).isNotEmpty()
    }
}
