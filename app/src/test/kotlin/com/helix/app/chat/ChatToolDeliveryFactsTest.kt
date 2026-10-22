package com.helix.app.chat

import com.helix.core.agent.SettledCall
import com.helix.core.model.BoundToolResult
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.Sha256
import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolDeliveryFactsTest {
    private val encoder = ChatToolMessageEncoder { _, _ -> "cancelled" }

    @Test fun savedResultAndSeparateLogIdentitySurviveTheModelHistoryPath() {
        val page = ToolResultReadTool.page("original/call", "{\"exitCode\":7}", 0, "bash")
        val result = BoundToolResult(page.toString(), Sha256("b".repeat(64)), false, 1)
        val draft =
            encoder.toolResultDraft(
                SettledCall("reader", ToolResultReadTool.NAME, ToolDispatchOutcome.Succeeded(result), "current/reader"),
                readerEnabled = true,
            )
        val history =
            com.helix.app.agent.ChatHistoryBuilder
                .toModelMessagesStrict(
                    listOf(
                        com.helix.app.agent.ChatHistoryBuilder
                            .PersistedRow("current", "TOOL", draft.kind, draft.content),
                    ),
                ).single()
                .text
        assertTrue(history.contains("\"contentKind\":\"tool_result\""))
        assertTrue(history.contains("\"logRef\":\"original/call\""))
        assertTrue(history.contains("\"logReadTool\":\"code.linux.job.read_log\""))
        assertTrue(history.contains("\"exitCode\":7"))
    }

    @Test fun failedActionDeliversEffectTruthAndLocalIdentityWithoutAFalseResultReaderHandle() {
        val failure =
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TIMEOUT,
                "response lost",
                sideEffectFree = false,
                requiresReview = true,
            )
        val message = encoder.toolResultDraft(SettledCall("wire-call", "device.share_text", failure, "turn/local-call"))
        val content = Json.parseToJsonElement(message.content).jsonObject
        assertEquals("TIMEOUT", content.getValue("status").jsonPrimitive.content)
        assertEquals("false", content.getValue("sideEffectFree").jsonPrimitive.content)
        assertEquals("true", content.getValue("requiresReview").jsonPrimitive.content)
        assertEquals("turn/local-call", content.getValue("callRef").jsonPrimitive.content)
        assertFalse(content.containsKey("resultRef"))
    }

    @Test fun anEarlierExecutorCutIsReportedSeparatelyFromModelPreviewTruncation() {
        val result = BoundToolResult("partial saved payload", Sha256("a".repeat(64)), true, 1)
        val message = encoder.toolResultDraft(SettledCall("wire", "read", ToolDispatchOutcome.Succeeded(result), "t/c"))
        val content = Json.parseToJsonElement(message.content).jsonObject
        assertEquals("true", content.getValue("executorPayloadTruncated").jsonPrimitive.content)
        assertEquals("a".repeat(64), content.getValue("executorOutputHash").jsonPrimitive.content)
    }
}
