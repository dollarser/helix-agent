package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.helix.app.chat.MessagePresentation
import com.helix.app.chat.MessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessagePresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val text = "Checking retained evidence before the next tool. ".repeat(12).trim()

    @Test fun completedLongProgressStartsCollapsedAndWholeHeaderRevealsIt() {
        render(MessagePresentation.COMPLETED_PROGRESS)
        assertTrue(layout().lineCount <= 3)
        compose.onNodeWithTag("message-progress-toggle-progress").performClick()
        assertTrue(layout().lineCount > 3)
        compose.onNodeWithText(text).assertTextContains(text)
        compose.onNodeWithTag("message-progress-toggle-progress").performClick()
        assertTrue(layout().lineCount <= 3)
    }

    @Test fun liveProgressAndUnclassifiedFinalAnswersRemainFullyVisible() {
        val presentation = mutableStateOf(MessagePresentation.PROGRESS)
        compose.setContent {
            MaterialTheme { Column(Modifier.width(240.dp)) { Body(presentation.value) } }
        }
        assertTrue(layout().lineCount > 3)
        compose.onNodeWithTag("message-progress-toggle-progress").assertDoesNotExist()
        val progressSize = layout().layoutInput.style.fontSize
        compose.runOnIdle { presentation.value = MessagePresentation.NORMAL }
        assertTrue(layout().lineCount > 3)
        assertTrue(layout().layoutInput.style.fontSize > progressSize)
    }

    @Test fun searchRevealsFoldedContentAndClearingSearchRestoresDisclosure() {
        val query = mutableStateOf<String?>("retained")
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.width(240.dp),
                ) { Body(MessagePresentation.COMPLETED_PROGRESS, query.value) }
            }
        }
        assertTrue(layout().lineCount > 3)
        compose.onNodeWithTag("message-progress-toggle-progress").assertDoesNotExist()
        compose.runOnIdle { query.value = null }
        assertTrue(layout().lineCount <= 3)
        compose.onNodeWithTag("message-progress-toggle-progress").assertExists()
    }

    @Test fun progressDisclosureDoesNotInvokeMessageActions() {
        var actions = 0
        compose.setContent {
            MaterialTheme {
                MessageRow(
                    MessageUi("progress", "assistant", text, presentation = MessagePresentation.COMPLETED_PROGRESS),
                    onFork = { actions++ },
                    onRegenerate = { actions++ },
                )
            }
        }
        repeat(2) { compose.onNodeWithTag("message-progress-toggle-progress").performClick() }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun thinkingOnlyHasNoEmptyBodyAndKeepsAnAccessibleDisclosure() {
        compose.setContent {
            MaterialTheme { MessageRow(MessageUi("thinking", "assistant", "<think>Inspect evidence</think>")) }
        }
        compose.onNodeWithTag("chat-message-body-thinking").assertDoesNotExist()
        val header = compose.onNodeWithTag("thinking-header-thinking").getUnclippedBoundsInRoot()
        val message = compose.onNodeWithTag("chat-message-toggle-thinking").getUnclippedBoundsInRoot()
        assertTrue("Thinking disclosure must remain easy to tap", (header.bottom - header.top) >= 48.dp)
        assertTrue(
            "A thinking-only message must not reserve a blank answer",
            (message.bottom - message.top) - (header.bottom - header.top) <= 8.dp,
        )
        compose.onNodeWithTag("thinking-header-thinking").performClick()
        compose.onNodeWithText("Inspect evidence").assertExists()
    }

    @Test fun streamedAnswerAppearsWithoutAnEmptyPlaceholderAfterThinking() {
        val content = mutableStateOf("<think>Inspect evidence")
        compose.setContent {
            MaterialTheme { MessageRow(MessageUi("stream", "assistant", content.value)) }
        }
        compose.onNodeWithTag("chat-message-body-stream").assertDoesNotExist()
        compose.runOnIdle { content.value = "<think>Inspect evidence</think>Verified result" }
        compose.onNodeWithText("Verified result").assertExists()
        compose.onNodeWithTag("thinking-header-stream").performClick()
        compose.onNodeWithTag("thinking-content-stream").assertDoesNotExist()
        compose.onNodeWithText("Verified result").assertExists()
    }

    private fun render(presentation: MessagePresentation) {
        compose.setContent { MaterialTheme { Column(Modifier.width(240.dp)) { Body(presentation) } } }
    }

    @androidx.compose.runtime.Composable
    @Suppress("FunctionName")
    private fun Body(
        presentation: MessagePresentation,
        query: String? = null,
    ) {
        AssistantMessageBody(
            MessageUi("progress", "assistant", text, presentation = presentation),
            Modifier.testTag("body"),
            query,
            false,
        )
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(
                text,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertEquals(1, results.size)
        return results.single()
    }
}
