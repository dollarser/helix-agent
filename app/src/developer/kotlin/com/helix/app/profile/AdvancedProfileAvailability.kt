package com.helix.app.profile

/**
 * Compile-time availability of the ADVANCED entry (ADR-0005: ADVANCED only
 * appears in the developer variant, and must be switched on explicitly after
 * the user reads the risk explanation; ADR-0006: the developer variant IS the
 * single direct-distribution main app, shown to users simply as "Helix").
 *
 * The in-app risk explanation shown before the explicit switch lives in the
 * `profile_advanced_risk_summary` string resource (HXA-069); it states the ADR
 * guarantees verbatim in product terms: the switch itself grants nothing — no
 * system permission is requested, no Runtime is installed, no Root session is
 * opened, no new network endpoint is reached; every capability is enabled
 * individually, scoped and revocable, by its own later milestone.
 */
internal object AdvancedProfileAvailability {
    const val ADVANCED_AVAILABLE: Boolean = true
}
