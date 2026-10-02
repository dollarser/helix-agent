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

    @Test fun messageActionsToggleAndUserMessagesNeverOfferFork() {
        compose.setContent {
            MaterialTheme {
                MessageRow(MessageUi("question", "user", "Question"), onFork = {})
            }
        }
        compose.onNodeWithTag("chat-copy-question").assertDoesNotExist()
        compose.onNodeWithTag("chat-message-toggle-question").performClick()
        compose.onNodeWithTag("chat-copy-question").assertIsDisplayed()
        compose.onNodeWithTag("chat-fork-question").assertDoesNotExist()
        compose.onNodeWithTag("chat-message-toggle-question").performClick()
        compose.onNodeWithTag("chat-copy-question").assertDoesNotExist()
    }

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
            compose.onNodeWithTag("chat-copy-$id").assertDoesNotExist()
            compose.onNodeWithTag("chat-message-toggle-$id").performClick()
            val bubble = compose.onNodeWithTag("chat-message-toggle-$id").getUnclippedBoundsInRoot()
            val copy = compose.onNodeWithTag("chat-copy-$id").getUnclippedBoundsInRoot()
            // Clickable Surface centers short content inside its 48dp minimum touch target.
            // Semantics reports visible bounds, so allow only that mandatory inset, not a spacer.
            val inset = ((48f - (bubble.bottom - bubble.top).value) / 2f).coerceAtLeast(0f)
            assertEquals(inset, (copy.top - bubble.bottom).value, 0.5f)
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
        compose.onNodeWithTag("chat-message-toggle-copy").performClick()
        compose.runOnIdle {
            clipboard.setText(
                androidx.compose.ui.text
                    .AnnotatedString(""),
            )
        }
        compose.onNodeWithTag("chat-copy-copy").performClick()
        awaitClipboard(clipboard, text)
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
        compose.onNodeWithText("Heading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("bold", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("val x = 1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("# Heading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-message-toggle-markdown").performClick()
        compose.runOnIdle {
            clipboard.setText(
                androidx.compose.ui.text
                    .AnnotatedString(""),
            )
        }
        compose.onNodeWithTag("chat-copy-markdown").performClick()
        awaitClipboard(clipboard, source)
        compose.runOnIdle { assertEquals(source, clipboard.getText()?.text) }
    }

    private fun awaitClipboard(
        clipboard: ClipboardManager,
        expected: String,
    ) {
        // Keep the real platform clipboard assertion; do not replace it with an in-memory fake.
        compose.waitUntil(5_000) { compose.runOnIdle { clipboard.getText()?.text == expected } }
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
