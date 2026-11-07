package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.infrastructure.conversation.RoomConversationHistoryReader
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationHistoryRequest
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ToolTimelineRow
import com.helix.feature.conversation.presentation.conversationScreenProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ChatScreenProjectionDeviceTest {
    @Test fun changingHistoryWindowDoesNotReintroduceCompletedLiveRows() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "chat-window-${UUID.randomUUID()}.db"
        val files = File(app.filesDir, name)
        val storage = HelixStorage.open(app, name, files)
        try {
            storage.sessions.create("s", "Window", null, null, 1)
            for (i in 0..2) {
                storage.turns.start("t$i", "s", i.toLong())
                storage.messages.append("m$i", "s", "t$i", "USER", "TEXT", "Message $i")
                storage.toolCalls.append("c$i", "t$i", "c$i", "read", "1", "{}", "COMPLETED")
                storage.turns.updateState(
                    storage.turns.resolve("t$i"),
                    com.helix.core.model.TurnState.INTERRUPTED,
                    0,
                    3,
                    null,
                )
            }
            val reader =
                RoomConversationHistoryReader(storage, app.appContainer.providerService, { _, _ -> "unused" }, { 0 })
            val projection = conversationScreenProjection { _, _ -> "unused" }
            val latest = projection.toolTimelineFor(history(reader, "s"), emptyList())
            assertEquals(3, latest.size)
            val older = projection.toolTimelineFor(history(reader, "s", latest, limit = 1, through = 0), latest)
            assertEquals(listOf("c0"), older.map { it.callId })
        } finally {
            storage.close()
            app.deleteDatabase(name)
            files.deleteRecursively()
        }
    }

    @Suppress("LongMethod") // One reopen round trip proves UI filtering and retained model protocol together.
    @Test
    fun toolProtocolStaysInModelHistoryButNeverBecomesAConversationBubbleAfterReopen() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "chat-projection-${UUID.randomUUID()}.db"
        val files = File(app.filesDir, name)
        var storage = HelixStorage.open(app, name, files)
        try {
            storage.sessions.create("session", "Projection", null, null, 1)
            storage.turns.start("turn", "session", 2)
            storage.toolCalls.append("call", "turn", "call", "read", "1", "{}", "FAILED", "Inspect file")
            storage.toolResults.append("result", "call", "FAILED", "fixture failure", null)
            val json = "{\"status\":\"SUCCEEDED\",\"result\":[1,2,3]}"
            val rows =
                listOf(
                    Triple("USER", ChatHistoryBuilder.KIND_TEXT, json),
                    Triple("ASSISTANT", ChatHistoryBuilder.KIND_TEXT, "```json\n$json\n```"),
                    Triple(
                        "ASSISTANT",
                        ChatHistoryBuilder.KIND_TOOL_CALLS,
                        "[{\"id\":\"call\",\"name\":\"code.javascript.run\",\"arguments\":\"{}\"}]",
                    ),
                    Triple(
                        "TOOL",
                        ChatHistoryBuilder.KIND_TOOL_RESULT,
                        """{"id":"call","tool":"code.javascript.run","status":"SUCCEEDED","summary":"output"}""",
                    ),
                    Triple("ASSISTANT", ContextCompaction.KIND, "internal summary"),
                    Triple("TOOL", ChatHistoryBuilder.KIND_TEXT, "legacy tool output"),
                    Triple("ASSISTANT", ChatHistoryBuilder.KIND_TEXT, ""),
                )
            rows.forEachIndexed { index, (role, kind, content) ->
                storage.messages.append("message-$index", "session", null, role, kind, content)
            }
            storage.close()
            storage = HelixStorage.open(app, name, files)
            val reader =
                RoomConversationHistoryReader(storage, app.appContainer.providerService, { _, _ -> "unused" }, { 0 })
            val projection = conversationScreenProjection { _, _ -> "unused" }
            val restored = projection.toolTimelineFor(history(reader, "session"), emptyList()).single()
            assertEquals("Inspect file", restored.modelIntent)
            assertEquals("{}", restored.requestSummary)
            assertEquals("fixture failure", restored.resultSummary)
            val live = listOf(restored.copy(durationMs = 400))
            val withDuration = projection.toolTimelineFor(history(reader, "session", live), live).single()
            assertEquals(400L, withDuration.durationMs)
            val visible = projection.messagesFor(history(reader, "session"))
            assertEquals(listOf("message-0", "message-1"), visible.map { it.id })
            assertEquals(listOf(json, "```json\n$json\n```"), visible.map { it.content })
            val persisted = storage.messages.listBySession("session")
            assertEquals(rows.size, persisted.size)
            val history =
                ChatHistoryBuilder.toModelMessagesStrict(
                    persisted.map {
                        ChatHistoryBuilder.PersistedRow(it.turnId, it.role, it.kind, storage.messages.readContent(it))
                    },
                )
            assertTrue(
                history.any {
                    it.toolCalls
                        .singleOrNull()
                        ?.id
                        ?.value == "call"
                },
            )
            assertTrue(history.any { it.role == ModelRole.TOOL && it.toolCallId?.value == "call" })
        } finally {
            storage.close()
            app.deleteDatabase(name)
            files.deleteRecursively()
        }
    }

    private fun history(
        reader: RoomConversationHistoryReader,
        sessionId: String,
        live: List<ToolTimelineRow> = emptyList(),
        limit: Int = 100,
        through: Long = Long.MAX_VALUE,
    ) = reader.read(
        ConversationHistoryRequest.capture(
            sessionId,
            ChatScreenState(emptyList(), sessionId, null, emptyList(), live, null, null, null, null)
                .copy(historyLimit = limit, historyThrough = through),
            0,
        ),
    )
}
