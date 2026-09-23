package com.helix.app.ui

import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessageUi
import com.helix.app.chat.ToolTimelineRow
import com.helix.app.chat.TurnUi
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSearchTest {
    private fun createScreen(
        messages: List<MessageUi> = emptyList(),
        tools: List<ToolTimelineRow> = emptyList(),
    ) = ChatScreenState(
        sessions = emptyList(),
        openSessionId = "session-1",
        badge = null,
        messages = messages,
        toolTimeline = tools,
        activeTurn = null,
        pendingDisclosure = null,
        blockedReason = null,
        retryTargetTurnId = null,
        turns = listOf(TurnUi("turn-1", TurnState.COMPLETED, null, null, false)),
    )

    @Test
    fun emptyConversationReturnsNoMatches() {
        val screen = createScreen()
        val matches = findMatches("test", screen)
        assertEquals(0, matches.size)
    }

    @Test
    fun matchesUserAndAssistantMessagesCaseInsensitively() {
        val screen =
            createScreen(
                messages =
                    listOf(
                        MessageUi("m1", "user", "How to use Kotlin Coroutines?", turnId = "turn-1"),
                        MessageUi("m2", "assistant", "Kotlin Coroutines provide async programming.", turnId = "turn-1"),
                    ),
            )

        val matches = findMatches("coroutines", screen)
        assertEquals(2, matches.size)
        assertEquals("m1", matches[0].targetId)
        assertEquals("m2", matches[1].targetId)
    }

    @Test
    fun matchesToolExecutionRows() {
        val screen =
            createScreen(
                messages =
                    listOf(
                        MessageUi("m1", "user", "Read this file", turnId = "turn-1"),
                        MessageUi("m2", "assistant", "I read the file.", turnId = "turn-1"),
                    ),
                tools =
                    listOf(
                        ToolTimelineRow(
                            turnId = "turn-1",
                            callId = "call-1",
                            toolName = "files.read",
                            requestSummary = "{\"path\": \"main.kt\"}",
                            stateLabel = "Completed",
                            resultSummary = "file content",
                            card = null,
                        ),
                    ),
            )

        val matches = findMatches("main.kt", screen)
        assertEquals(1, matches.size)
        assertEquals("call-1", matches[0].targetId)
    }

    @Test
    fun controllerNavigationCyclesThroughMatches() {
        val screen =
            createScreen(
                messages =
                    listOf(
                        MessageUi("m1", "user", "match one", turnId = "turn-1"),
                        MessageUi("m2", "assistant", "match two", turnId = "turn-1"),
                        MessageUi("m3", "assistant", "match three", turnId = "turn-1"),
                    ),
            )

        val controller = ConversationSearchController()
        controller.onQueryChange("match", screen)

        assertEquals(3, controller.matches.size)
        assertEquals(0, controller.currentMatchIndex)
        assertEquals("m1", controller.currentMatch?.targetId)

        // Next
        controller.nextMatch()
        assertEquals(1, controller.currentMatchIndex)
        assertEquals("m2", controller.currentMatch?.targetId)

        controller.nextMatch()
        assertEquals(2, controller.currentMatchIndex)
        assertEquals("m3", controller.currentMatch?.targetId)

        // Wrap next to 0
        controller.nextMatch()
        assertEquals(0, controller.currentMatchIndex)
        assertEquals("m1", controller.currentMatch?.targetId)

        // Wrap prev to 2
        controller.prevMatch()
        assertEquals(2, controller.currentMatchIndex)
        assertEquals("m3", controller.currentMatch?.targetId)

        // Clear
        controller.clear()
        assertEquals("", controller.query)
        assertEquals(0, controller.matches.size)
        assertNull(controller.currentMatch)
    }
}
