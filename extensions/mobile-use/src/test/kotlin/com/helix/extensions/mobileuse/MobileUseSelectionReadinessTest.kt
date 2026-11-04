package com.helix.extensions.mobileuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileUseSelectionReadinessTest {
    @Test fun unavailableConfigurationFailsBeforeBackendSetup() {
        assertEquals(
            "MOBILE_USE_CONFIGURATION_UNREADABLE",
            MobileUseSelectionReadiness.error(false) {
                error("Unreadable configuration must not become a whole-phone fallback")
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
