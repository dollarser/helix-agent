package com.helix.app.agent

import com.helix.app.chat.FileToolArguments
import com.helix.core.agent.ContextCompiler
import com.helix.core.agent.ContextSegments
import com.helix.core.agent.ContextSourceRow
import com.helix.core.agent.ContextSourceSnapshot
import com.helix.core.agent.ContextSummaryFormat
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ProviderContextSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileHistoryPathsTest {
    private val directory = FileScopePath("ws", "project")
    private val arguments = """{"path":"scope:ws:project/报告.txt","content":"scope:ws:project/do-not-rewrite.txt"}"""

    @Test fun failedResultsAndOrdinaryQuotedTextStayVerbatim() {
        val failed = rows().last().copy(content = rows().last().content?.replace("SUCCEEDED", "POLICY_DENIED"))
        val messages = ChatHistoryBuilder.toModelMessagesStrict(listOf(failed), directory)
        assertTrue(messages.single().text.contains("scope:ws:project/报告.txt"))
        val text = ChatHistoryBuilder.PersistedRow("turn", "USER", "TEXT", arguments)
        assertEquals(arguments, ChatHistoryBuilder.toModelMessagesStrict(listOf(text), directory).single().text)
    }

    @Test fun projectedArgumentsBindBackToTheSameExecutionIdentity() {
        val projected = FileHistoryPaths.project("write", arguments, directory)
        assertEquals(
            "报告.txt",
            Json
                .parseToJsonElement(projected)
                .jsonObject
                .getValue("path")
                .jsonPrimitive.content,
        )
        assertEquals(
            Json.parseToJsonElement(arguments),
            Json.parseToJsonElement(FileToolArguments.bindRequest(projected, directory)),
        )
        // A later request in another directory cannot point at its same-named file.
        assertEquals(arguments, FileHistoryPaths.project("write", arguments, FileScopePath("ws", "other")))
        assertEquals(arguments, FileHistoryPaths.project("write", arguments, FileScopePath("other", "project")))
    }

    @Test fun searchResultsKeepCrossDirectoryIdentityAndUserContentVerbatim() {
        val payload =
            """{"path":"scope:ws:project","matches":["scope:ws:project/报告.txt","scope:other:报告.txt"],""" +
                """"content":"scope:ws:project/报告.txt"}"""
        val result =
            Json
                .parseToJsonElement(
                    FileHistoryPaths.project("files.search", payload, directory, result = true),
                ).jsonObject
        assertEquals(".", result.getValue("path").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""["报告.txt","scope:other:报告.txt"]"""), result["matches"])
        assertEquals("scope:ws:project/报告.txt", result.getValue("content").jsonPrimitive.content)
    }

    @Test fun unknownToolsAndUnstructuredOrTruncatedPayloadsAreNotRewritten() {
        for (name in listOf("mcp.read", "plugin.read", "code.javascript.run", "tools.result.read")) {
            assertEquals(arguments, FileHistoryPaths.project(name, arguments, directory))
        }
        val payloads =
            listOf(
                "not json scope:ws:project/a",
                "[]",
                """{"preview":"scope:ws:project/a","resultRef":"t/c"}""",
            )
        for (payload in payloads) {
            assertEquals(payload, FileHistoryPaths.project("read", payload, directory, result = true))
        }
        assertEquals(arguments, FileHistoryPaths.project("read", arguments, null))
    }

    @Test fun replayAndCompactionUseTheSameProjectionButSummarySourceKeepsStablePaths() {
        val rows = rows()
        val replay = ChatHistoryBuilder.toModelMessagesStrict(rows, directory)
        val source =
            rows.mapIndexed { index, row ->
                ContextSourceRow(
                    "m$index",
                    "turn",
                    index.toLong(),
                    row.role,
                    requireNotNull(row.kind),
                    row.content,
                    0,
                    row.kind == ChatHistoryBuilder.KIND_TOOL_CALLS,
                    ChatHistoryBuilder.toModelMessagesStrict(listOf(row), directory),
                )
            }
        assertEquals(replay, source.flatMap { it.messages })
        assertTrue(
            source
                .last()
                .content
                .orEmpty()
                .contains("scope:ws:project/报告.txt"),
        )
        assertTrue(replay.last().text.contains("\"path\":\"报告.txt\""))
        val request = TurnContextRequest("test", replay, emptyList(), 512, ReasoningEffort.OFF, directory = directory)
        val snapshot = ContextSourceSnapshot(source, null, "turn", null, 0)
        assertNotNull(ContextSegments.remainingRequest(snapshot, emptySet(), request))
        assertEquals(listOf(ModelRole.ASSISTANT, ModelRole.TOOL), replay.map { it.role })
        assertEquals(
            replay
                .first()
                .toolCalls
                .single()
                .id,
            replay.last().toolCallId,
        )
        // Persisted source can be reopened and projected for a different binding without mutation.
        val switched = ChatHistoryBuilder.toModelMessagesStrict(rows, FileScopePath("ws", "elsewhere"))
        assertTrue(switched.last().text.contains("scope:ws:project/报告.txt"))
        assertEquals(rows(), rows)
    }

    @Test fun summaryGenerationReceivesAnchoredReferencesEvenWhenTheRequestUsesShortPaths() {
        val history = rows() + rows().map { it.copy(content = it.content?.replace("call", "next-call")) }
        val source =
            history.mapIndexed { index, row ->
                ContextSourceRow(
                    "m$index",
                    "turn",
                    index.toLong(),
                    row.role,
                    requireNotNull(row.kind),
                    row.content,
                    0,
                    row.kind == ChatHistoryBuilder.KIND_TOOL_CALLS,
                    ChatHistoryBuilder.toModelMessagesStrict(listOf(row), directory),
                )
            }
        val request =
            TurnContextRequest(
                "test",
                ChatHistoryBuilder.toModelMessagesStrict(history, directory),
                emptyList(),
                512,
                ReasoningEffort.OFF,
                directory = directory,
            )
        val compiler = ContextCompiler(ContextSummaryFormat("Summarize evidence", "Preserve references"))
        val plan =
            requireNotNull(
                compiler.plan(
                    ContextSourceSnapshot(source, null, "turn", null, 0),
                    request,
                    RunControlConfig(AgentMode.ACT, true, TurnBudgets(8, 9, 128_000, 4096, 160_000)),
                    ProviderContextSettings(manualWindow = 128_000),
                    true,
                ),
            )
        assertTrue(
            plan.request.messages
                .joinToString { it.text }
                .contains("scope:ws:project/报告.txt"),
        )
        assertEquals(2, plan.retainedRequest.messages.size)
        assertTrue(
            plan.retainedRequest.messages
                .last()
                .text
                .contains("\"path\":\"报告.txt\""),
        )
    }

    private fun rows(): List<ChatHistoryBuilder.PersistedRow> =
        listOf(
            ChatHistoryBuilder.PersistedRow(
                "turn",
                "ASSISTANT",
                ChatHistoryBuilder.KIND_TOOL_CALLS,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "call")
                            put("name", "write")
                            put("arguments", arguments)
                        },
                    )
                }.toString(),
            ),
            ChatHistoryBuilder.PersistedRow(
                "turn",
                "TOOL",
                ChatHistoryBuilder.KIND_TOOL_RESULT,
                buildJsonObject {
                    put("id", "call")
                    put("tool", "write")
                    put("status", "SUCCEEDED")
                    put("summary", """{"path":"scope:ws:project/报告.txt","bytesWritten":10}""")
                }.toString(),
            ),
        )
}
