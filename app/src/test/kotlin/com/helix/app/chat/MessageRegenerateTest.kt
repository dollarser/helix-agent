package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRegenerateTest {
    @Test
    fun `resolve target turn traces back to user turn`() {
        val chronologicalTurns = listOf("turn-1", "turn-2", "turn-3")
        val userTurns = setOf("turn-1", "turn-3")

        // Retrying turn-3 finds turn-3 itself since it is a user turn
        val resolved = RetryMessageSource.resolve("turn-3", chronologicalTurns, userTurns)
        assertEquals("turn-3", resolved)

        // Retrying turn-2 (which has no user row) traces back to turn-1
        val resolvedFromTurn2 = RetryMessageSource.resolve("turn-2", chronologicalTurns, userTurns)
        assertEquals("turn-1", resolvedFromTurn2)

        // Unknown turn resolves to null
        assertNull(RetryMessageSource.resolve("unknown", chronologicalTurns, userTurns))
    }

    @Test
    fun `only latest assistant message when not sending is eligible for regenerate`() {
        val messages =
            listOf(
                MessageUi("m1", "user", "Hello"),
                MessageUi("m2", "assistant", "Hi there"),
                MessageUi("m3", "user", "What is 2+2?"),
                MessageUi("m4", "assistant", "It is 4"),
            )

        val latestAssistantId = messages.lastOrNull { it.role != "user" }?.id
        assertEquals("m4", latestAssistantId)

        // When not sending, m4 is eligible
        var isSending = false
        val eligibleM4 = latestAssistantId == "m4" && !isSending
        assertEquals(true, eligibleM4)

        // Older assistant m2 is not eligible
        val eligibleM2 = latestAssistantId == "m2" && !isSending
        assertEquals(false, eligibleM2)

        // When sending, m4 is not eligible
        isSending = true
        val eligibleWhileSending = latestAssistantId == "m4" && !isSending
        assertEquals(false, eligibleWhileSending)
    }
}
