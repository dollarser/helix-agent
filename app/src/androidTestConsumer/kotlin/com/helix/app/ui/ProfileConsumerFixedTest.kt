package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-028 consumer-variant profile pin (ADR-0005/0006): the consumer build is
 * the restricted channel — it shows the STANDARD profile as FIXED (no Advanced
 * entry is rendered at all) and its profile store refuses any switch to
 * ADVANCED (fail-closed; there is no hidden switch and no remote-config path).
 */
@RunWith(AndroidJUnit4::class)
class ProfileConsumerFixedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun consumerShowsFixedStandardAndStoreRefusesAdvanced() {
        composeRule.resetDeterministicUiState()

        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("settings-profile-current").assertIsDisplayed()
        composeRule.onNodeWithText("当前：Standard（默认）").assertIsDisplayed()
        // No Advanced entry exists in the consumer build.
        composeRule.onNodeWithTag("settings-advanced-switch").assertIsNotDisplayed()
        composeRule.onNodeWithTag("settings-advanced-exit").assertIsNotDisplayed()
        composeRule.onNodeWithTag("settings-advanced-absent").assertIsDisplayed()

        // Store-level fail-closed: even a direct switch to ADVANCED is refused,
        // and the profile stays STANDARD.
        assertThrows(
            "the consumer store must refuse switchTo(ADVANCED)",
            IllegalArgumentException::class.java,
        ) {
            composeRule.container().profileStore.switchTo(SafetyProfile.ADVANCED)
        }
        assertEquals(SafetyProfile.STANDARD, composeRule.container().profileStore.profile)
    }
}
