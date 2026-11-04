package com.androgrid.worker.capability

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Interface abstracting Android OS and hardware capability discovery.
 *
 * Traces to: [REQ-VER-002]
 */
interface SystemInfoProvider {
    /**
     * Discovers total online CPU processing cores.
     *
     * @return count of available CPU cores (minimum 1).
     */
    fun availableProcessors(): Int

    /**
     * Discovers total RAM installed on device in bytes.
     *
     * @return total RAM in bytes.
     */
    fun totalMemoryBytes(): Long

    /**
     * Reports exact OS release string matching Build.VERSION.RELEASE.
     *
     * @return Android OS release version string.
     */
    fun osVersion(): String

    /**
     * Reports primary supported CPU ABI matching Build.SUPPORTED_ABIS[0].
     *
     * @return primary ABI string.
     */
    fun supportedAbi(): String
}

/**
 * Production Android system information provider querying Bionic runtime and ActivityManager.
 *
 * Traces to: [REQ-VER-002]
 */
class AndroidSystemInfoProvider(
    private val context: Context
) : SystemInfoProvider {

    override fun availableProcessors(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (cores > 0) cores else 1
    }

    override fun totalMemoryBytes(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo)
                memoryInfo.totalMem
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    override fun osVersion(): String {
        return Build.VERSION.RELEASE ?: "unknown"
    }

    override fun supportedAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        return if (!abis.isNullOrEmpty()) abis[0] else "unknown"
    }
}
