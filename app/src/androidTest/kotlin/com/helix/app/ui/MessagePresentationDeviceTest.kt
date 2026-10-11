package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
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
