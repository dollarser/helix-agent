package com.helix.app.deviceaccess

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityPresentationTest {
    @Test fun configuredButUnboundRequiresRecoveryWithoutClaimingRevocation() {
        val view = accessibilityPresentation(1, 0, 1)
        assertEquals(R.string.device_access_accessibility_disconnected, view.status)
        assertEquals(R.string.device_access_accessibility_restore, view.action)
        assertEquals(R.string.device_access_accessibility_restore_help, view.help)
    }

    @Test fun incompleteGrantTakesPriorityOverConnection() {
        assertEquals(R.string.permissions_not_granted, accessibilityPresentation(1, 1, 2).status)
    }

    @Test fun readyRequiresAllDeclaredServicesGrantedAndBound() {
        assertEquals(R.string.device_access_backend_ready, accessibilityPresentation(2, 2, 2).status)
    }

    @Test fun noServicesDoesNotClaimReady() {
        assertEquals(R.string.device_access_accessibility_none, accessibilityPresentation(0, 0, 0).status)
    }
}
