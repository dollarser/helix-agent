package com.helix.runtime.proot.client

import com.helix.runtime.proot.ipc.UnavailableCause

/**
 * The supervisor's local (no-bind) pre-checks as a pure, JVM-testable unit
 * (HXA-083; ADR-0007 section 6.7 step 1). Priority order is significant: an
 * uninstalled package reports NOT_INSTALLED (never SIGNATURE_MISMATCH), a
 * force-stopped one reports PACKAGE_FORCED_STOPPED before the enabled check,
 * and the signature check runs last because it is the most expensive query.
 *
 * Returns the failing stable cause, or null when all local checks pass and the
 * process may be worth starting.
 */
class ProotLocalStateCheck(
    private val probe: ProotRuntimeProbe,
) {
    fun check(): UnavailableCause? =
        when {
            !probe.isInstalled() -> UnavailableCause.NOT_INSTALLED
            probe.isStopped() -> UnavailableCause.PACKAGE_FORCED_STOPPED
            !probe.isEnabled() -> UnavailableCause.PACKAGE_DISABLED
            !probe.isSameSigningSet() -> UnavailableCause.SIGNATURE_MISMATCH
            else -> null
        }
}
