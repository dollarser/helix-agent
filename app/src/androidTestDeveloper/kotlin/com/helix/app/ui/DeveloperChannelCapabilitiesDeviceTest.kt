package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Channel availability never creates grants, performs network calls or modifies provider records. */
class DeveloperChannelCapabilitiesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun developerCapabilitiesAreAvailableWithoutModeSelectionAndSurviveRecreation() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val networkBefore = container.providerService.networkOperations.value
        val providersBefore = container.providerService.rows.value
        compose.navigateTo("settings/permissions")
        assertEquals(SafetyProfile.ADVANCED, container.profileStore.profile)
        compose.onNodeWithTag("settings-profile-options").assertDoesNotExist()
        compose.onNodeWithTag("settings-risk-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings-system-permissions").assertExists()
        compose.onNodeWithTag("settings-lan-options").assertExists()
        compose.onNodeWithTag("settings-egress-options").assertExists()
        captureChatLayout(compose.activity, "fixed-channel-permissions")
        assertEquals(networkBefore, container.providerService.networkOperations.value)
        assertEquals(providersBefore, container.providerService.rows.value)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.navigateTo("settings/permissions")
        assertEquals(SafetyProfile.ADVANCED, compose.container().profileStore.profile)
        compose.onNodeWithTag("settings-profile-options").assertDoesNotExist()
        compose.onNodeWithTag("settings-advanced-exit").assertDoesNotExist()
        compose.navigateTo("sessions")
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("drawer-profile").assertDoesNotExist()
        compose.onNodeWithTag("drawer-profile-switch").assertDoesNotExist()
        captureChatLayout(compose.activity, "fixed-channel-drawer")
    }
}
