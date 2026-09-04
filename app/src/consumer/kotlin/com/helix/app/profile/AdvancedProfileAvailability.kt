package com.helix.app.profile

/**
 * Compile-time availability of the ADVANCED entry (ADR-0005: ADVANCED only
 * appears in the developer variant; ADR-0006: consumer is the restricted
 * channel build that never offers a path from Standard into Advanced).
 *
 * The consumer build sets [ADVANCED_AVAILABLE] to `false`: the settings UI
 * renders no Advanced entry at all, and [com.helix.app.profile.PersistedSafetyProfileStore]
 * refuses any switch to ADVANCED (fail-closed). There is no hidden switch and
 * no remote-config path (ADR-0005).
 */
internal object AdvancedProfileAvailability {
    const val ADVANCED_AVAILABLE: Boolean = false
}
