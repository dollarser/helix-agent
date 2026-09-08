package com.helix.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationTimelineDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val count = mutableStateOf(40)
    private val stream = mutableStateOf("stream start")
    private val session = mutableStateOf("first")
    private val height = mutableStateOf(360)
    private val completed = mutableStateOf(false)

    @Test fun growingAndCompletedRepliesStayAtTheirEndAcrossResizeAndSessionChange() {
        render()
        assertEndVisible()
        compose.runOnIdle { stream.value = "long response\n".repeat(100) + "END" }
        assertEndVisible()
        compose.runOnIdle { completed.value = true }
        assertEndVisible()
        compose.runOnIdle { height.value = 240 }
        assertEndVisible()
        compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
        compose.runOnIdle { session.value = "second" }
        assertEndVisible()
    }

    @Test fun readingHistoryKeepsItsAnchorUntilExplicitJump() = verifyReadingHistory(accessibility = false)

    @Test fun semanticScrollKeepsReadingPositionOnNewContent() = verifyReadingHistory(accessibility = true)

    @Test fun emptyInstructionsStartAtTheTopThenRealContentFollowsTheEnd() {
        val hasConversation = mutableStateOf(false)
        compose.setContent {
            ConversationTimeline(
                "empty-transition",
                hasConversation.value,
                Modifier.height(180.dp),
                followContent = hasConversation.value,
            ) {
                item { Text("Start of instructions", Modifier.testTag("instruction-start")) }
                items((0 until 20).toList()) { Text("Line $it", Modifier.height(48.dp)) }
            }
        }
        compose.onNodeWithTag("instruction-start").assertIsDisplayed()
        compose.onNodeWithTag("chat-scroll-latest").assertDoesNotExist()
        compose.runOnIdle { hasConversation.value = true }
        assertEndVisible()
    }

    private fun verifyReadingHistory(accessibility: Boolean) {
        render()
        assertEndVisible()
        if (accessibility) {
            compose.onNodeWithTag("chat-timeline").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -900f) }
        } else {
            compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown() }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
        val before = visibleRows()
        assertTrue(before.isNotEmpty())
        compose.runOnIdle {
            count.value += 5
            stream.value = "new content\n".repeat(80) + "END"
        }
        compose.waitForIdle()
        assertEquals(before, visibleRows())
        compose.onNodeWithTag("chat-scroll-latest").performClick()
        assertEndVisible()
        compose.runOnIdle { stream.value += "\nmore after explicit jump" }
        assertEndVisible()
    }

    private fun render() {
        compose.setContent {
            ConversationTimeline(
                session.value,
                listOf(count.value, stream.value, completed.value),
                Modifier.height(height.value.dp),
            ) {
                items((0 until count.value).toList(), key = { "row-$it" }) {
                    Text("History $it", Modifier.height(64.dp).testTag("history-row"))
                }
                item(key = if (completed.value) "completed" else "streaming") {
                    Text(stream.value, Modifier.testTag("reply"))
                }
            }
        }
    }

    private fun assertEndVisible() {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("chat-timeline").fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithTag("chat-timeline-end")
        end.assertIsDisplayed()
        val bounds = end.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        compose.onNodeWithTag("chat-scroll-latest").assertDoesNotExist()
    }

    private fun visibleRows(): List<Pair<String, Float>> {
        val viewport = compose.onNodeWithTag("chat-timeline").fetchSemanticsNode().boundsInRoot
        return compose
            .onAllNodesWithTag("history-row")
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom }
            .map { it.config[SemanticsProperties.Text].joinToString() to it.boundsInRoot.top }
    }
}
