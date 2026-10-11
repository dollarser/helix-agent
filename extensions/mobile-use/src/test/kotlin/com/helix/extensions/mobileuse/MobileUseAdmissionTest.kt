package com.helix.extensions.mobileuse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseAdmissionTest {
    private val ready = MobileUseAdmission(true, true, true, true, false, false, false)

    @Test fun readyHasNoRefusal() = assertNull(ready.error())

    @Test fun missingFactsFailClosedWithSpecificReasons() {
        assertTrue(ready.copy(hasSession = false).error()!!.startsWith("MOBILE_USE_SESSION_REQUIRED"))
        assertTrue(ready.copy(hasGrant = false).error()!!.startsWith("MOBILE_USE_AUTHORIZATION_UNAVAILABLE"))
        assertTrue(ready.copy(scopeMatches = false).error()!!.startsWith("MOBILE_USE_SCOPE_CHANGED"))
        assertTrue(ready.copy(backendReady = false).error()!!.startsWith("MOBILE_USE_BACKEND_UNAVAILABLE"))
        assertTrue(ready.copy(locked = true).error()!!.startsWith("MOBILE_USE_DEVICE_LOCKED"))
    }

    @Test fun cancellationAndDeadlineCannotBeHiddenByBackendFailure() {
        assertTrue(ready.copy(cancelled = true, backendReady = false).error()!!.startsWith("MOBILE_USE_CANCELLED"))
        assertTrue(
            ready.copy(expired = true, backendReady = false).error()!!.startsWith("MOBILE_USE_DEADLINE_EXCEEDED"),
        )
    }

    @Test fun staleScopeIsNotMisreportedAsBackendLossButCancellationStillWins() {
        val stale = ready.copy(scopeMatches = false, backendReady = false, scopeSupplied = true)
        assertTrue(stale.error()!!.startsWith("MOBILE_USE_SCOPE_CHANGED"))
        assertTrue(stale.copy(cancelled = true).error()!!.startsWith("MOBILE_USE_CANCELLED"))
    }
}
