package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

class BundledPluginsNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bundledMobileUseIsDiscoverableWithoutOpeningManagementTab() {
        compose.resetDeterministicUiState()
        compose.navigateTo("extensions")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("bundled-plugin-mobile-use").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("mobile-use-session-settings")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("screen-session-settings").assertIsDisplayed()
    }
}
