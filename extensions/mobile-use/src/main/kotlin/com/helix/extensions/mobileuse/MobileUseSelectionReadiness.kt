package com.helix.extensions.mobileuse

/** Passive setup facts only. Selecting a plugin never starts a backend or grants system access. */
internal object MobileUseSelectionReadiness {
    fun error(
        configured: Boolean,
        screenBackendReady: () -> Boolean,
    ): String? =
        when {
            !configured -> "MOBILE_USE_NOT_CONFIGURED"
            !screenBackendReady() -> "MOBILE_USE_BACKEND_UNAVAILABLE"
            else -> null
        }
}
