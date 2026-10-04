package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** HXA-226: one complete management surface per capability; shortcuts only deep-link to it. */
class IaAuthorityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawerAndSettingsExposeOnlyPrimaryAuthorities() {
        compose.resetDeterministicUiState()

        compose.onNodeWithTag("open-navigation").performClick()
        listOf("capabilities", "readiness", "permissions", "audit").forEach { legacy ->
            compose.onNodeWithTag("navigation-$legacy").assertDoesNotExist()
        }
        compose.onNodeWithTag("navigation-group-configure").assertDoesNotExist()
        compose.onNodeWithTag("navigation-setup").assertDoesNotExist()
        listOf("models", "extensions").forEach { route ->
            compose.onNodeWithTag("navigation-$route").performScrollTo().assertIsDisplayed()
        }

        compose.onNodeWithTag("navigation-group-settings").performScrollTo().performClick()
        settingsDrawerEntries.forEach { entry ->
            compose.onNodeWithTag("navigation-${entry.route}").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("navigation-settings").performScrollTo().performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        compose.onNodeWithTag("settings-about").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-open-defaults").assertDoesNotExist()
        compose.onNodeWithTag("settings-open-permissions").assertDoesNotExist()
        compose.onNodeWithTag("settings-open-audit").assertDoesNotExist()
        compose.onNodeWithTag("provider-add").assertDoesNotExist()
        compose.onNodeWithTag("connector-import").assertDoesNotExist()
        compose.onNodeWithTag("settings-proot-status").assertDoesNotExist()
    }

    @Test
    fun focusedAuthoritiesOwnTheirCompleteManagementSurface() {
        compose.resetDeterministicUiState()

        compose.navigateTo("models")
        compose.onNodeWithTag("screen-models").assertIsDisplayed()
        compose.onNodeWithTag("provider-group-ON_DEVICE_ASSET").assertIsDisplayed()
        if (com.helix.app.profile.AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("provider-group-MANAGED_ACCOUNT").assertDoesNotExist()
        }
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("provider-add").performScrollTo().assertIsDisplayed()

        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-tab-manage").performScrollTo().performClick()
        compose.onNodeWithTag("connector-import").performScrollTo().assertIsDisplayed()

        compose.navigateTo(SETTINGS_AUDIT_ROUTE)
        compose.onNodeWithTag("diagnostics-open-readiness").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-open-readiness").performClick()
        compose.onNodeWithTag("capability-readiness").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("diagnostics-open-capabilities").performScrollTo().assertIsDisplayed()
        compose.navigateTo(SETUP_RUNTIME_ROUTE)
        compose.onNodeWithTag("screen-setup-runtime").assertIsDisplayed()

        compose.navigateTo("settings/permissions")
        compose.onNodeWithTag("screen-settings-permissions").assertIsDisplayed()
        compose.onNodeWithTag("settings-system-permissions").assertIsDisplayed()
    }
}
