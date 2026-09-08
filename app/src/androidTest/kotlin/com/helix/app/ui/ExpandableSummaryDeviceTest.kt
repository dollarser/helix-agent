package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExpandableSummaryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTextExpandsAndCollapsesWhileShortReplacementNeedsNoAction() {
        val text = mutableStateOf("目标与模型标识 Long provider and goal description ".repeat(6))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(200.dp)) {
                    ExpandableSummary(text.value, MaterialTheme.typography.titleSmall, "summary")
                }
            }
        }
        val summary = compose.onNodeWithTag("summary")
        val collapsedHeight = summary.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("summary-toggle").assertIsDisplayed().performClick()
        assertTrue(summary.getUnclippedBoundsInRoot().let { it.bottom - it.top } > collapsedHeight)
        compose.onNodeWithTag("summary-toggle").performClick()
        assertEquals(collapsedHeight, summary.getUnclippedBoundsInRoot().let { it.bottom - it.top })
        compose.onNodeWithTag("summary-toggle").performClick()
        compose.runOnIdle { text.value = "Short" }
        compose.onNodeWithTag("summary-toggle").assertDoesNotExist()
        summary.assertIsDisplayed()
    }
}
