package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage
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
            val projection =
                ChatScreenProjection(storage, app.appContainer.providerService, { _, _ -> "unused" }, { 0 })
            val latest = projection.toolTimelineFor("s", emptyList())
            assertEquals(3, latest.size)
            val older = projection.toolTimelineFor("s", latest, listOf(storage.messages.resolve("m0")), 0)
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
            val projection =
                ChatScreenProjection(storage, app.appContainer.providerService, { _, _ -> "unused" }, { 0 })
            val restored = projection.toolTimelineFor("session", emptyList()).single()
            assertEquals("Inspect file", restored.modelIntent)
            assertEquals("{}", restored.requestSummary)
            assertEquals("fixture failure", restored.resultSummary)
            val withDuration = projection.toolTimelineFor("session", listOf(restored.copy(durationMs = 400))).single()
            assertEquals(400L, withDuration.durationMs)
            val screen = ChatScreenState(emptyList(), null, null, emptyList(), emptyList(), null, null, null, null)
            val visible = projection.messagesFor("session", screen)
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
}
