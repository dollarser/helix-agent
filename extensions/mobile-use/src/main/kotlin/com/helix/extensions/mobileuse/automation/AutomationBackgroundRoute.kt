package com.helix.extensions.mobileuse.automation

import android.os.IBinder

/** Host-created route, never supplied by model arguments. The owner checks the original live task. */
data class AutomationBackgroundRoute(
    val key: String,
    val owner: IBinder,
    val allowMoveFromMain: Boolean,
)

/** Platform flags, not permission grants. No ALWAYS_UNLOCKED flag or lock-screen bypass. */
internal fun backgroundDisplayFlags(
    sdk: Int,
    trusted: Boolean,
): Int {
    var flags = 1 or (1 shl 3) or (1 shl 6) or (1 shl 8)
    if (trusted) flags = flags or (1 shl 10)
    if (trusted && sdk >= 34) flags = flags or (1 shl 14) or (1 shl 16)
    return flags
}
