package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

class DiagnosticReportNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsOpensTheProductionContentFreeReport() {
        compose.resetDeterministicUiState()
        compose.navigateTo(SETTINGS_AUDIT_ROUTE)
        compose.onNodeWithTag("diagnostics-open").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("diagnostics-preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("diagnostics-preview").assertTextContains("helix.process-diagnostics", substring = true)
        compose.onNodeWithTag("diagnostics-preview").assertTextContains("appVersion", substring = true)
        compose.onNodeWithTag("diagnostics-copy").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-close").performClick()
        compose.onNodeWithTag("diagnostics-preview").assertDoesNotExist()
    }
}
