package com.helix.app

import com.helix.app.internal.LineStore

/**
 * The first-launch privacy-notice gate (HXA-028: "首次启动隐私说明"). The flag
 * is set the once the user dismisses the notice; app data reset clears the
 * preferences and the notice returns (ADR-0006: fresh install/reset →
 * STANDARD + first-launch flow).
 */
class FirstLaunchStore(
    private val backing: LineStore,
) {
    val noticeSeen: Boolean
        get() = backing.lines(KEY).contains("1")

    fun markSeen() {
        backing.setLines(KEY, listOf("1"))
    }

    /** Test seam: re-arm the first-launch gate. */
    fun reset() {
        backing.setLines(KEY, emptyList())
    }

    private companion object {
        const val KEY = "first_launch"
    }
}
