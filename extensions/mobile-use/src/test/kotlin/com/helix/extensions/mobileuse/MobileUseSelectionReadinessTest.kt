package com.helix.extensions.mobileuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileUseSelectionReadinessTest {
    @Test fun missingConfigurationIsResolvedBeforeBackendSetup() {
        assertEquals(
            "MOBILE_USE_NOT_CONFIGURED",
            MobileUseSelectionReadiness.error(false) {
                error("Backend must not be queried before scope configuration")
            },
        )
    }

    @Test fun connectionLossAndRecoveryAreReadFreshWithoutRememberingPermissionAsReadiness() {
        var connected = false
        val current = { MobileUseSelectionReadiness.error(true) { connected } }
        assertEquals("MOBILE_USE_BACKEND_UNAVAILABLE", current())
        connected = true
        assertNull(current())
        connected = false
        assertEquals("MOBILE_USE_BACKEND_UNAVAILABLE", current())
    }
}
