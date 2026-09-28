package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.helix.app.storage.StorageUsageCategory
import com.helix.app.storage.StorageUsageEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StorageUsageDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitOpenFailureRetryPartialResultAndReopenAtLargeFont() {
        var loads = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    StorageUsageSection {
                        loads++
                        if (loads == 1) error("synthetic private detail")
                        listOf(StorageUsageEntry(StorageUsageCategory.MEMORY, loads.toLong(), false))
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithTag("storage-usage-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("storage-usage-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("storage-usage-refresh").assertIsDisplayed().performClick()
        compose
            .onNodeWithTag("storage-usage-memory")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("2 B", substring = true)
        compose.onNodeWithTag("storage-usage-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("storage-usage-memory").assertDoesNotExist()
        compose.onNodeWithTag("storage-usage-open").performClick()
        compose.onNodeWithTag("storage-usage-memory").performScrollTo().assertTextContains("3 B", substring = true)
        compose.runOnIdle { assertEquals(3, loads) }
    }
}
