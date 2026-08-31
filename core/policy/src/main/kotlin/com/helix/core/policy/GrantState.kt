package com.helix.core.policy

/**
 * System-verified capability state (platform capabilities doc section 2; architecture doc 9.1
 * `capability_grants.systemState`).
 *
 * The four states are the documented state space (platform capabilities doc section 6.2:
 * "Unavailable / Denied / Granted / Lost"). Only [GRANTED] combined with a fresh, system-verified
 * [CapabilityGrant] is usable at execution time (see [CapabilityGrant.isUsable]); [DENIED],
 * [UNAVAILABLE] and [LOST] all fail closed.
 */
enum class GrantState {
    /** The real system state currently grants the capability. */
    GRANTED,

    /** The capability exists on the platform but is actively not granted (never requested, denied or revoked). */
    DENIED,

    /** The platform or this build offers no such capability at all (no root integration, no
     * accessibility service component, API level too low). */
    UNAVAILABLE,

    /** Previously granted, but the runtime connection or session is gone (service disconnected,
     * root session expired). Execution-time checks must treat this as unavailable. */
    LOST,
}
