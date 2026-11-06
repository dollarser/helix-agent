package com.helix.app

import com.helix.app.internal.LineStore

internal enum class StartupOnboarding { NOTICE, PERMISSIONS, COMPLETE }

/** Notice acknowledgement and optional permission review share one durable write. */
class FirstLaunchStore(
    private val backing: LineStore,
) {
    val noticeSeen: Boolean
        get() = backing.lines(KEY).contains("1")

    internal val onboarding: StartupOnboarding
        get() =
            when {
                !noticeSeen -> StartupOnboarding.NOTICE
                backing.lines(KEY).contains("permissions") -> StartupOnboarding.PERMISSIONS
                else -> StartupOnboarding.COMPLETE
            }

    internal fun acceptNotice() {
        backing.setLines(KEY, listOf("1", "permissions"))
    }

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
