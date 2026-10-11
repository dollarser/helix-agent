package com.helix.extensions.mobileuse

/** A refusal describes current facts; it never grants scope or retries an external operation. */
internal data class MobileUseAdmission(
    val hasSession: Boolean,
    val hasGrant: Boolean,
    val scopeMatches: Boolean,
    val backendReady: Boolean,
    val cancelled: Boolean,
    val expired: Boolean,
    val locked: Boolean,
    val scopeSupplied: Boolean = false,
) {
    fun error(): String? =
        when {
            cancelled -> {
                "MOBILE_USE_CANCELLED: The call was cancelled; do not retry automatically."
            }

            expired -> {
                "MOBILE_USE_DEADLINE_EXCEEDED: The call deadline expired."
            }

            !hasSession -> {
                "MOBILE_USE_SESSION_REQUIRED: Use a Helix conversation."
            }

            !hasGrant -> {
                "MOBILE_USE_AUTHORIZATION_UNAVAILABLE: " +
                    "Check this conversation's Mobile Use selection and scope settings."
            }

            locked -> {
                "MOBILE_USE_DEVICE_LOCKED: Unlock the phone before continuing."
            }

            scopeSupplied && !scopeMatches -> {
                "MOBILE_USE_SCOPE_CHANGED: Request a fresh call with the current authorization."
            }

            !backendReady -> {
                "MOBILE_USE_BACKEND_UNAVAILABLE: Restore a backend in Helix system permissions."
            }

            !scopeMatches -> {
                "MOBILE_USE_SCOPE_CHANGED: Request a fresh call with the current authorization."
            }

            else -> {
                null
            }
        }
}
