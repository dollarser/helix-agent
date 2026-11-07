package com.helix.app.chat

import com.helix.app.R
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.infrastructure.conversation.RoomConversationHistoryReader
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import com.helix.feature.conversation.application.ConversationHistoryRequest
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ConversationEntry
import com.helix.feature.conversation.presentation.conversationScreenProjection
import org.junit.Assert.assertEquals
import org.junit.Test
import com.helix.feature.conversation.R as ConversationR

class ChatTimelineOrderDeviceTest {
    @Test fun originalProtocolOrderSurvivesReverseToolRowInsertionAndProjectionReload() {
        ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
            val storage = f.storage
            val batchSequence = prepareHistory(storage)
            val reader =
                RoomConversationHistoryReader(
                    storage,
                    f.service,
                    { _, _ -> "state" },
                    { ConversationR.string.tool_state_failed },
                )
            val projection = conversationScreenProjection { _, _ -> "state" }
            val state =
                ChatScreenState(
                    sessions = emptyList(),
                    openSessionId = "timeline",
                    messages = emptyList(),
                    toolTimeline = emptyList(),
                    selectedModel = null,
                    activeTurn = null,
                    pendingDisclosure = null,
                    blockedReason = null,
                    retryTargetTurnId = null,
                )
            repeat(2) {
                val snapshot = reader.read(ConversationHistoryRequest.capture("timeline", state, it.toLong()))
                val entry =
                    ConversationEntry(
                        "turn",
                        projection.messagesFor(snapshot),
                        projection.toolTimelineFor(snapshot, emptyList()),
                        emptyList(),
                    )
                assertEquals(
                    listOf("user", "before", "tool-first", "tool-second", "answer", "tool-missing"),
                    entry.timeline.map { it.key },
                )
                assertEquals(null, entry.tools.last().position)
                assertEquals(
                    batchSequence,
                    entry.tools
                        .first()
                        .position
                        ?.sequence,
                )
            }
        }
    }

    private fun prepareHistory(storage: com.helix.core.storage.HelixStorage): Long {
        storage.sessions.create("timeline", "Timeline", "p", "a", 1)
        storage.turns.start("turn", "timeline", 1)

        fun text(
            id: String,
            role: String,
            content: String,
        ) = storage.messages.append(id, "timeline", "turn", role, "TEXT", content)
        text("user", "USER", "Start")
        text("before", "ASSISTANT", "Checking")
        val batch =
            storage.messages.append(
                "batch",
                "timeline",
                "turn",
                "ASSISTANT",
                ChatHistoryBuilder.KIND_TOOL_CALLS,
                """[{"id":"wire-first","localId":"first","name":"read","arguments":"{}"},""" +
                    """{"id":"wire-second","localId":"second","name":"read","arguments":"{}"}]""",
            )
        val missing =
            storage.messages.append(
                "missing",
                "timeline",
                "turn",
                "ASSISTANT",
                ChatHistoryBuilder.KIND_TOOL_CALLS,
                "[]",
            )
        storage.contentStore.delete(
            com.helix.core.storage.content.ContentRef
                .parse(requireNotNull(missing.contentRef)),
        )
        storage.messages.append(
            "malformed",
            "timeline",
            "turn",
            "ASSISTANT",
            ChatHistoryBuilder.KIND_TOOL_CALLS,
            "not JSON",
        )
        text("answer", "ASSISTANT", "Done")
        for (call in listOf("second", "first", "missing")) {
            storage.toolCalls.append(call, "turn", call, "read", "1", "{}", "COMPLETED")
        }
        return batch.sequence
    }
}
