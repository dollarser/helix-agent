package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextHistory
import com.helix.app.agent.ContextHistoryMapping
import com.helix.core.agent.ContextCompiler
import com.helix.core.agent.ContextSegments
import com.helix.core.agent.ContextSourceSnapshot
import com.helix.core.agent.ContextSummaryFormat
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ProviderContextSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PathHistoryDeviceTest {
    @Test fun reopenedRoomHistoryPreservesIdentityAcrossDirectoryChangesAndCompaction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "path-history-${UUID.randomUUID()}.db"
        val directory = File(context.filesDir, name)
        val original = FileScopePath("one", "project/报告.txt").toModelReference()
        var storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "Path history fixture", null, null, 1000)
            repeat(2) { index ->
                val args = """{"path":"$original"}"""
                val calls =
                    "[" +
                        buildJsonObject {
                            put("id", "call-$index")
                            put("name", "read")
                            put("arguments", args)
                        }.toString() + "]"
                val result =
                    buildJsonObject {
                        put("id", "call-$index")
                        put("tool", "read")
                        put("status", "SUCCEEDED")
                        put("summary", """{"path":"$original","content":"ORIGINAL"}""")
                    }.toString()
                storage.messages.append("call-$index", "s", null, "ASSISTANT", "TOOL_CALLS", calls)
                storage.messages.append("result-$index", "s", null, "TOOL", "TOOL_RESULT", result)
            }
            val before = storage.messages.listBySession("s").map { storage.messages.readContent(it) }
            storage.close()
            storage = HelixStorage.open(context, name, directory)
            for (cwd in listOf(
                FileScopePath("one", "project"),
                FileScopePath("one", "other"),
                FileScopePath("two", "project"),
            )) {
                verifyProjection(storage, cwd, original)
            }
            assertEquals(before, storage.messages.listBySession("s").map { storage.messages.readContent(it) })
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    private fun verifyProjection(
        storage: HelixStorage,
        cwd: FileScopePath,
        original: String,
    ) {
        val history = ContextHistory.load(storage, "s")
        val source = ContextHistoryMapping.rows(storage, history.rows, cwd) { history.contents[it.id] }
        val messages =
            ChatHistoryBuilder.toModelMessagesStrict(
                history.rows.map {
                    ChatHistoryBuilder.PersistedRow(it.turnId, it.role, it.kind, history.contents[it.id], it.id)
                },
                cwd,
            )
        assertEquals(source.flatMap { it.messages }, messages)
        val expected = if (cwd == FileScopePath("one", "project")) "报告.txt" else original
        assertEquals(
            """{"path":"$expected"}""",
            messages
                .first()
                .toolCalls
                .single()
                .argumentsJson,
        )
        assertTrue(source.all { it.content.orEmpty().contains(original) })
        val request = TurnContextRequest("test", messages, emptyList(), 512, ReasoningEffort.OFF, directory = cwd)
        val snapshot = ContextSourceSnapshot(source, null, "next", null, 0)
        assertNotNull(ContextSegments.remainingRequest(snapshot, emptySet(), request))
        val plan =
            requireNotNull(
                ContextCompiler(ContextSummaryFormat("Summarize", "Preserve sources")).plan(
                    snapshot,
                    request,
                    RunControlConfig(AgentMode.ACT, true, TurnBudgets(maxOutputTokens = 4096)),
                    ProviderContextSettings(manualWindow = 128000),
                    true,
                ),
            )
        assertTrue(
            plan.request.messages
                .joinToString { it.text }
                .contains(original),
        )
        assertEquals(2, plan.retainedRequest.messages.size)
    }
}
