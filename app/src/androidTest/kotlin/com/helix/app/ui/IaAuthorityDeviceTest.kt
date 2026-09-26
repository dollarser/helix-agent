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
        compose.onNodeWithTag("navigation-group-configure").performClick()
        listOf("models", "extensions", "setup").forEach { route ->
            compose.onNodeWithTag("navigation-$route").performScrollTo().assertIsDisplayed()
        }

        compose.onNodeWithTag("navigation-settings").performScrollTo().performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-defaults").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-permissions").assertIsDisplayed()
        compose.onNodeWithTag("settings-open-audit").assertIsDisplayed()
        compose.onNodeWithTag("provider-add").assertDoesNotExist()
        compose.onNodeWithTag("connector-import").assertDoesNotExist()
        compose.onNodeWithTag("settings-proot-status").assertDoesNotExist()
    }

    @Test
    fun focusedAuthoritiesOwnTheirCompleteManagementSurface() {
        compose.resetDeterministicUiState()

        compose.navigateTo("models")
        compose.onNodeWithTag("screen-models").assertIsDisplayed()
        compose.onNodeWithTag("provider-add").performScrollTo().assertIsDisplayed()

        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-tab-manage").performScrollTo().performClick()
        compose.onNodeWithTag("connector-import").performScrollTo().assertIsDisplayed()

        compose.navigateTo("setup")
        compose.onNodeWithTag("setup-open-readiness").assertIsDisplayed()
        compose.onNodeWithTag("setup-open-capabilities").assertIsDisplayed()
        compose.onNodeWithTag("setup-open-runtime").assertIsDisplayed()

        compose.navigateTo("settings/permissions")
        compose.onNodeWithTag("screen-settings-permissions").assertIsDisplayed()
        compose.onNodeWithTag("settings-system-permissions").assertIsDisplayed()
    }
}
