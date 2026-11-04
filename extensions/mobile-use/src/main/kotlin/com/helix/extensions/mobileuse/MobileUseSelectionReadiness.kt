package com.helix.extensions.mobileuse

/** Passive setup facts only. Selecting a plugin never starts a backend or grants system access. */
internal object MobileUseSelectionReadiness {
    fun error(
        configurationAvailable: Boolean,
        screenBackendReady: () -> Boolean,
    ): String? =
        when {
            !configurationAvailable -> "MOBILE_USE_CONFIGURATION_UNREADABLE"
            !screenBackendReady() -> "MOBILE_USE_BACKEND_UNAVAILABLE"
            else -> null
        }
}
