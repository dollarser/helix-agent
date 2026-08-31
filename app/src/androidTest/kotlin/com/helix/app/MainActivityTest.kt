package com.helix.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
