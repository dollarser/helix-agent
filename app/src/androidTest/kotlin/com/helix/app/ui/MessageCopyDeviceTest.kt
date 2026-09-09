package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.helix.app.chat.MessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageCopyDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyControlsFollowTextWithoutAnExtraSpacerForBothRoles() {
        compose.setContent {
            MaterialTheme {
                Column {
                    MessageRow(MessageUi("user-spacing", "user", "User text"))
                    MessageRow(MessageUi("assistant-spacing", "assistant", "Assistant text"))
                }
            }
        }
        listOf("user-spacing", "assistant-spacing").forEach { id ->
            val body = compose.onNodeWithTag("chat-message-body-$id").getUnclippedBoundsInRoot()
            val copy = compose.onNodeWithTag("chat-copy-$id").getUnclippedBoundsInRoot()
            assertEquals(body.bottom, copy.top)
            assertTrue("Keep copy target usable", (copy.bottom - copy.top).value >= 48f)
        }
    }

    @Test fun fullMessageCopiesWithoutTruncationAndAllowsLongPress() {
        lateinit var clipboard: ClipboardManager
        val text = "First line\nSecond line with selectable words"
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme { MessageRow(MessageUi("copy", "assistant", text)) }
        }
        compose.onNodeWithTag("chat-copy-copy").performClick()
        compose.runOnIdle { assertEquals(text, clipboard.getText()?.text) }
        compose.onNodeWithTag("chat-message-assistant").performTouchInput { longClick() }
    }

    @Test fun markdownRendersButFullCopyKeepsTheOriginalSource() {
        lateinit var clipboard: ClipboardManager
        val source = "# Heading\n\n**bold**\n\n```kotlin\nval x = 1\n```"
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme { MessageRow(MessageUi("markdown", "assistant", source)) }
        }
        compose.onNodeWithText("Heading").assertIsDisplayed()
        compose.onNodeWithText("bold").assertIsDisplayed()
        compose.onNodeWithText("val x = 1").assertIsDisplayed()
        compose.onNodeWithText("# Heading").assertDoesNotExist()
        compose.onNodeWithTag("chat-copy-markdown").performClick()
        compose.runOnIdle { assertEquals(source, clipboard.getText()?.text) }
    }

    @Test fun markdownTableRendersCellsInScrollableColumns() {
        compose.setContent {
            MaterialTheme { MarkdownText("| Left | Right |\n| --- | --- |\n| a | b |") }
        }
        compose.onNodeWithText("Left").assertIsDisplayed()
        compose.onNodeWithText("Right").assertIsDisplayed()
        compose.onNodeWithText("a").assertIsDisplayed()
        compose.onNodeWithText("b").assertIsDisplayed()
    }
}
