package com.helix.app.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSystemNavigationDeviceTest {
    @Test fun missingBoundServiceDoesNotProvePendingBindingWasRemoved() {
        val component = "com.helix.agent.developer/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        assertFalse(
            GrantedAccessibilityReconnect.frameworkBindingCleared(
                "Bound services:{}\nBinding services:{{$component}}",
                component,
            ),
        )
        assertTrue(
            GrantedAccessibilityReconnect.frameworkBindingCleared("Bound services:{}\nBinding services:{}", component),
        )
        assertFalse(GrantedAccessibilityReconnect.frameworkBindingCleared("Bound services:{}", component))
    }

    @Test fun brightnessShadeDismissalDoesNotGrantOtherSystemControls() {
        assertTrue(GuardSystemNavigation.allowed(null, "com.android.systemui", "dismiss_notification_shade"))
        assertTrue(GuardSystemNavigation.allowed(null, "com.android.systemui", "back"))
        assertFalse(GuardSystemNavigation.allowed(null, "com.android.settings", "dismiss_notification_shade"))
        assertFalse(
            GuardSystemNavigation.allowed("com.arduia.expense", "com.android.systemui", "dismiss_notification_shade"),
        )
        for (action in listOf("power_dialog", "lock_screen", "notifications", "headset_hook", "dpad_center", null)) {
            assertFalse(GuardSystemNavigation.allowed(null, "com.android.systemui", action))
        }
    }
}
