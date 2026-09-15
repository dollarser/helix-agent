package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.helix.app.agent.ChatContextUsage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContextWindowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownUsageAndPressureAreDistinctAndInspectable() {
        val usage = mutableStateOf(ChatContextUsage())
        compose.setContent { MaterialTheme { ContextWindowIndicator(usage.value) } }
        compose.onNodeWithText("?").assertIsDisplayed()
        compose.runOnIdle { usage.value = ChatContextUsage(8500, 10000) }
        compose.onNodeWithText("85%").assertIsDisplayed()
        compose.onNodeWithTag("chat-context-window").performClick()
        compose.onNodeWithText("8500", substring = true).assertIsDisplayed()
    }

    @Test fun compactRingKeepsPercentageReadableAndTouchTargetUsableAtLargeFonts() {
        val scale = mutableStateOf(1f)
        val usage = mutableStateOf(ChatContextUsage(8000, 10000))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                MaterialTheme { ContextWindowIndicator(usage.value) }
            }
        }
        val small = compose.onNodeWithTag("chat-context-ring", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Default circle is compact", (small.right - small.left).value <= 30f)
        val target = compose.onNodeWithTag("chat-context-window").getUnclippedBoundsInRoot()
        assertTrue("Keep accessible touch target", (target.bottom - target.top).value >= 48f)
        compose.runOnIdle {
            scale.value = 1.8f
            usage.value = ChatContextUsage(10000, 10000, estimatedAfterCompaction = true)
        }
        val ring = compose.onNodeWithTag("chat-context-ring", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("≈100%", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Full percentage fits ring", label.left >= ring.left && label.right <= ring.right)
        compose.onNodeWithTag("chat-context-window").performClick()
        compose.onNodeWithText("10000", substring = true).assertIsDisplayed()
    }
}
