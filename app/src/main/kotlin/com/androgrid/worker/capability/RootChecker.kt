package com.androgrid.worker.capability

import android.os.Build
import java.io.File

/**
 * Interface defining root detection capability for Android devices.
 *
 * Traces to: [INV-006], [REQ-VER-002]
 */
interface RootChecker {
    /**
     * Evaluates whether the device possesses root access or binary indicators.
     *
     * @return true if root binaries or test-keys build tags are present.
     */
    fun isDeviceRooted(): Boolean
}

/**
 * Standard Android root detection implementation inspecting filesystem su paths and build tags.
 *
 * Traces to: [INV-006]
 */
class StandardRootChecker : RootChecker {

    companion object {
        private val SU_PATHS = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
    }

    override fun isDeviceRooted(): Boolean {
        return checkBuildTags() || checkSuPaths()
    }

    private fun checkBuildTags(): Boolean {
        val tags = Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    private fun checkSuPaths(): Boolean {
        return try {
            SU_PATHS.any { path -> File(path).exists() }
        } catch (_: Exception) {
            false
        }
    }
}
