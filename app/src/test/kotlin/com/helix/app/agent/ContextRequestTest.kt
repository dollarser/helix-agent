package com.helix.app.agent

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRequestTest {
    @Test fun largeHistoryCanReachCompactionBeforeWireMessageValidation() {
        val context =
            ChatContextRequest(
                "fixture",
                List(600) { ModelMessage(ModelRole.USER, "message $it") },
                emptyList(),
                512,
                ReasoningEffort.OFF,
            )
        assertTrue(context.inputTokens() > 0)
        assertThrows(IllegalArgumentException::class.java) { context.modelRequest() }
        assertEquals(
            2,
            context
                .copy(messages = context.messages.takeLast(2))
                .modelRequest()
                .messages.size,
        )
    }

    @Test fun summaryChunkingPreservesSurrogatePairsAndFullHistory() {
        val history = "a".repeat(59999) + "😀" + "b".repeat(300000)
        val chunks = ContextCompaction.summaryMessages(history).drop(1)
        assertEquals(history, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
        assertTrue(chunks.all { it.text.length <= 60_000 })
    }
}
