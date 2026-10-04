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

class StorageUsageNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsReadsAllProductionCategoriesWithoutDeletingData() {
        compose.resetDeterministicUiState()
        compose.navigateTo(SETTINGS_STORAGE_ROUTE)
        compose.onNodeWithTag("screen-settings-storage").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("storage-usage-records").fetchSemanticsNodes().isNotEmpty()
        }
        for (category in listOf("records", "workspaces", "memory")) {
            compose.onNodeWithTag("storage-usage-$category").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("storage-usage-records").assertDoesNotExist()
    }
}
