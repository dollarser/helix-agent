package com.helix.runtime.proot.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.helix.runtime.proot.core.PtyPackageReplacement
import kotlin.math.abs

/** Do not infer a killed process tree from lastUpdateTime alone (adb supports --dont-kill). */
internal fun packageReplacement(
    context: Context,
    boot: Int?,
): PtyPackageReplacement? {
    if (Build.VERSION.SDK_INT < 30) return null
    val updatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    val manager = context.getSystemService(ActivityManager::class.java)
    val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 64)
    val killedAt =
        exits
            .filter {
                it.processName == "${context.packageName}:proot" &&
                    isPackageReplacementExit(Build.VERSION.SDK_INT, it.reason, it.timestamp, updatedAt)
            }.maxOfOrNull { it.timestamp }
    return killedAt?.let {
        PtyPackageReplacement(boot, updatedAt, it, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }
}

internal fun isPackageReplacementExit(
    api: Int,
    reason: Int,
    exitedAt: Long,
    updatedAt: Long,
): Boolean =
    if (api >= 34) {
        reason == EXIT_PACKAGE_UPDATED
    } else {
        // Android 11–13 use this reason for both updates and package force-stop.
        api >= 30 && reason == EXIT_USER_REQUESTED && abs(exitedAt - updatedAt) <= 60_000
    }

// Stable ApplicationExitInfo reason values. This API-aware decoder performs no framework field access.
private const val EXIT_PACKAGE_UPDATED = 16
private const val EXIT_USER_REQUESTED = 10
