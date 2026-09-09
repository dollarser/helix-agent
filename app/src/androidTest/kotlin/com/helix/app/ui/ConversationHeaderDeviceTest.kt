package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationHeaderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowHeaderRetainsNavigationAndDetailsAtLargeFont() {
        var back = 0
        var created = 0
        var navigations = 0
        var renames = 0
        var tasks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        AdaptiveConversationHeader(
                            "A long conversation title that must not displace actions",
                            { back++ },
                            { created++ },
                            onNavigation = { navigations++ },
                            onRename = { renames++ },
                            onTasks = { tasks++ },
                        ) { Text("Session settings fixture") }
                    }
                }
            }
        }
        listOf("open-navigation", "chat-back", "chat-new-session", "chat-conversation-details").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= 0.dp && bounds.right <= 240.dp)
            assertTrue(
                "$tag touch bounds: $bounds",
                bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp,
            )
        }
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("chat-title").performClick()
        compose.onNodeWithTag("chat-back").performClick()
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.runOnIdle {
            assertEquals(1, navigations)
            assertEquals(1, renames)
            assertEquals(1, back)
            assertEquals(1, created)
        }
        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithText("Session settings fixture").assertIsDisplayed()
        compose.onNodeWithTag("chat-conversation-details-close").performClick()
        compose.onNodeWithText("Session settings fixture").assertDoesNotExist()
        compose.onNodeWithTag("chat-conversation-details").performClick()
        compose.onNodeWithTag("background-tasks-open").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, tasks) }
        compose.onNodeWithText("Session settings fixture").assertDoesNotExist()
    }
}
