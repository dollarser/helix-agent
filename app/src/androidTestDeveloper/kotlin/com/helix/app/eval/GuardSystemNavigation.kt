package com.helix.app.eval

/** Task navigation only; never grants unrelated SystemUI controls. */
internal object GuardSystemNavigation {
    fun allowed(
        target: String?,
        active: String?,
        action: String?,
    ): Boolean =
        action in setOf("back", "home") ||
            (target == null && action == "quick_settings") ||
            (target == null && active == "com.android.systemui" && action == "dismiss_notification_shade")
}
