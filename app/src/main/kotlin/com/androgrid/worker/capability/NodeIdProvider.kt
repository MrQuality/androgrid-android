package com.androgrid.worker.capability

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Provider generating persistent, hardware-safe unique node identifiers.
 * Avoids restricted device serial APIs on Android 10+ (API 29+) which throw [SecurityException].
 *
 * Traces to: [REQ-OP-001], [REQ-VER-001]
 */
object NodeIdProvider {

    private const val PREFS_NAME = "androgrid_node_identity"
    private const val KEY_NODE_ID = "node_id"

    /**
     * Retrieves or generates a persistent unique Node ID stored in private SharedPreferences.
     *
     * @param context Android application context.
     * @return Persistent unique node identifier.
     */
    @Synchronized
    fun getOrCreateNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_NODE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val sanitizedModel = Build.MODEL.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        val shortUuid = UUID.randomUUID().toString().substring(0, 8)
        val generatedId = "node_${sanitizedModel}_$shortUuid"

        prefs.edit().putString(KEY_NODE_ID, generatedId).apply()
        return generatedId
    }

    /**
     * Fallback for test environments or stateless contexts where SharedPreferences is unavailable.
     *
     * @return Safe non-privileged node identifier.
     */
    fun getFallbackNodeId(): String {
        val sanitizedModel = Build.MODEL.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        return "node_$sanitizedModel"
    }
}
