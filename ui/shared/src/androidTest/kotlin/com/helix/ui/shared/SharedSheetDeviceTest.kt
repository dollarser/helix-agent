package com.helix.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SharedSheetDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun requireIsolatedApplication() {
        requireSharedTestApplication(ApplicationProvider.getApplicationContext())
    }

    @Test fun closeDelegatesExactlyOnceAndRemovesOnlyThePresentation() {
        var shown by mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                if (shown) {
                    SharedSheet("Synthetic details", "synthetic-shared", {
                        dismissals++
                        shown = false
                    }) { Text("Synthetic body") }
                }
            }
        }
        compose.onNodeWithTag("synthetic-shared-sheet").assertIsDisplayed()
        compose.onNodeWithText("Synthetic body").assertIsDisplayed()
        compose.onNodeWithTag("synthetic-shared-close").performClick()
        compose.onNodeWithTag("synthetic-shared-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
