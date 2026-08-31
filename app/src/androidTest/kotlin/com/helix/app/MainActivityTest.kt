package com.helix.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shellOpensEveryDestinationAndNavigatesToBrowser() {
        // HXA-028: a fresh install shows the first-launch privacy notice over the
        // shell; dismiss it deterministically (absent on re-runs where the
        // acknowledgement already persisted). waitForIdle first so the initial
        // composition (the gate) has settled before the presence check.
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithTag("first-launch-continue").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("first-launch-continue").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("screen-sessions").assertIsDisplayed()
        composeRule.onNodeWithTag("open-navigation").performClick()

        ShellDestination.entries.forEach { destination ->
            composeRule
                .onNodeWithTag("navigation-${destination.route}")
                .assertIsDisplayed()
        }

        composeRule.onNodeWithTag("navigation-browser").performClick()
        composeRule.onNodeWithTag("screen-browser").assertIsDisplayed()
    }
}
