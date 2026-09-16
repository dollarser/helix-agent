package com.helix.app.connector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectorUiDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsConnectorImportEntrySurvivesActivityRecreation() {
        composeRule.resetDeterministicUiState()
        composeRule.navigateTo("settings")
        waitForSettingsRows()
        composeRule.onNodeWithTag("connector-import").performScrollTo()
        composeRule.waitUntil(10_000) { composeRule.onNodeWithTag("connector-import").isDisplayed() }
        composeRule.onNodeWithTag("connector-import").assertIsDisplayed()
        // Wait for the new Activity to resume before scrolling its composition.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        waitForSettingsRows()
        composeRule.onNodeWithTag("connector-import").performScrollTo()
        composeRule.waitUntil(10_000) { composeRule.onNodeWithTag("connector-import").isDisplayed() }
        composeRule.onNodeWithTag("connector-import").assertIsDisplayed()
    }

    private fun waitForSettingsRows() {
        // The asynchronous approval list is ABOVE this entry. Its initial empty state
        // changes the scroll range after recreation, even when Compose is idle.
        val lastTool =
            composeRule
                .container()
                .toolApprovalSettings
                .rows()
                .last()
                .toolName
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("tool-approval-row-$lastTool").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
