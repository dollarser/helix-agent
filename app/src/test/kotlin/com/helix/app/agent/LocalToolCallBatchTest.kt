package com.helix.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalToolCallBatchTest {
    @Test fun repeatedProviderIdsAcrossRoundsKeepIndependentLocalAuthority() {
        var serial = 0
        val wire = listOf(BufferedModelToolCall("same-provider-id", "read", "{}"))
        val first = LocalToolCallBatch(wire) { "local-${serial++}" }
        val second = LocalToolCallBatch(wire) { "local-${serial++}" }
        assertNotEquals(first.calls.single().callId, second.calls.single().callId)
        assertEquals("same-provider-id", first.wireId(first.calls.single().callId))
        assertEquals("same-provider-id", second.wireId(second.calls.single().callId))
        assertThrows(NoSuchElementException::class.java) { first.wireId(second.calls.single().callId) }
    }

    @Test fun parallelResultsMapByLocalIdentityRatherThanCompletionOrder() {
        var serial = 0
        val batch =
            LocalToolCallBatch(
                listOf(
                    BufferedModelToolCall("wire-a", "read", "{\"path\":\"a\"}"),
                    BufferedModelToolCall("wire-b", "read", "{\"path\":\"b\"}"),
                ),
            ) { "local-${serial++}" }
        assertEquals(listOf("wire-b", "wire-a"), batch.calls.reversed().map { batch.wireId(it.callId) })
        assertEquals("{\"path\":\"a\"}", batch.calls.first().arguments)
    }

    @Test fun persistedLocalMappingDoesNotRewriteProviderHistory() {
        val messages =
            ChatHistoryBuilder.toModelMessagesStrict(
                listOf(
                    ChatHistoryBuilder.PersistedRow(
                        "turn",
                        "ASSISTANT",
                        "TOOL_CALLS",
                        """[{"id":"wire-original","localId":"local-private","name":"read","arguments":"{}"}]""",
                    ),
                    ChatHistoryBuilder.PersistedRow(
                        "turn",
                        "TOOL",
                        "TOOL_RESULT",
                        """{"id":"wire-original","tool":"read","status":"SUCCEEDED","summary":"ok"}""",
                    ),
                ),
            )
        assertEquals(
            "wire-original",
            messages
                .first()
                .toolCalls
                .single()
                .id.value,
        )
        assertEquals("wire-original", messages.last().toolCallId?.value)
    }

    @Test fun invalidLocalIdsFailBeforeDispatch() {
        val wire = listOf(BufferedModelToolCall("a", "read", "{}"), BufferedModelToolCall("b", "read", "{}"))
        assertThrows(IllegalArgumentException::class.java) { LocalToolCallBatch(wire) { "" } }
        assertThrows(IllegalArgumentException::class.java) { LocalToolCallBatch(wire) { "duplicate" } }
    }
}
