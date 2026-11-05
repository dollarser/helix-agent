package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileConsumerFixedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun consumerChannelHasNoCapabilityModeSelectorOrDeveloperEntries() {
        composeRule.resetDeterministicUiState()
        composeRule.navigateTo("settings/permissions")
        composeRule.onNodeWithTag("settings-profile-options").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-advanced-switch").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-egress-options").assertDoesNotExist()
        assertEquals(SafetyProfile.STANDARD, composeRule.container().profileStore.profile)
        composeRule.activityRule.scenario.recreate()
        assertEquals(SafetyProfile.STANDARD, composeRule.container().profileStore.profile)
    }
}
