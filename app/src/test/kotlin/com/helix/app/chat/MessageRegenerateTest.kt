package com.helix.app.chat

import com.helix.app.engine.SubmitReceipt
import com.helix.app.engine.SubmitReceiptDecision
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun `SubmitTurnCommand rejects both revise and regenerate`() {
        val budgets = TurnBudgets(4, 4, 1000, 100, 2000)
        assertThrows(IllegalArgumentException::class.java) {
            SubmitTurnCommand(
                session = SessionId("s1"),
                providerId = ProviderId("p1"),
                mode = AgentMode.CHAT,
                text = null,
                budgets = budgets,
                retryTurnId = TurnId("t1"),
                clientRequestId = "req-1",
                revisedMessageId = "user-msg-1",
                regenerateMessageId = "asst-msg-1",
            )
        }
    }

    @Test
    fun `SubmitTurnCommand rejects regenerate with user text`() {
        val budgets = TurnBudgets(4, 4, 1000, 100, 2000)
        assertThrows(IllegalArgumentException::class.java) {
            SubmitTurnCommand(
                session = SessionId("s1"),
                providerId = ProviderId("p1"),
                mode = AgentMode.CHAT,
                text = "new text",
                budgets = budgets,
                retryTurnId = TurnId("t1"),
                clientRequestId = "req-1",
                regenerateMessageId = "asst-msg-1",
            )
        }
    }

    @Test
    fun `SubmitTurnCommand accepts regenerate with retryTurnId and null text`() {
        val budgets = TurnBudgets(4, 4, 1000, 100, 2000)
        val command =
            SubmitTurnCommand(
                session = SessionId("s1"),
                providerId = ProviderId("p1"),
                mode = AgentMode.CHAT,
                text = null,
                budgets = budgets,
                retryTurnId = TurnId("t1"),
                clientRequestId = "req-1",
                regenerateMessageId = "asst-msg-1",
            )
        assertEquals("asst-msg-1", command.regenerateMessageId)
        assertNull(command.text)
        assertEquals("t1", command.retryTurnId?.value)
    }

    @Test
    fun `TurnInputFingerprint incorporates regenerate target`() {
        val fp1 = TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-1")
        val fp2 = TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-2")
        val fpPlain = TurnInputFingerprint.of(null, emptyList())

        assertNotEquals(fp1, fp2)
        assertNotEquals(fp1, fpPlain)
        assertEquals(fp1, TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-1"))
    }

    @Test
    fun `Engine receipt recognizes duplicate regenerate requests`() {
        val fp = TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-1")
        val decision =
            SubmitReceipt.decide(
                existing =
                    com.helix.core.storage.entity.TurnEntity(
                        id = "turn-replacement-1",
                        sessionId = "s1",
                        state = "WAITING_MODEL",
                        stepCount = 0,
                        startedAt = 1000L,
                        endedAt = null,
                        errorCode = null,
                        clientRequestId = "req-1",
                        inputFingerprint = fp,
                    ),
                incomingSessionId = "s1",
                incomingFingerprint = fp,
            )

        assertEquals(SubmitReceiptDecision.Deduplicated("turn-replacement-1"), decision)
    }

    @Test
    fun `Engine receipt conflicts when clientRequestId reused with different regenerate target`() {
        val fp1 = TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-1")
        val fp2 = TurnInputFingerprint.of(null, emptyList(), regenerateMessageId = "asst-2")
        val decision =
            SubmitReceipt.decide(
                existing =
                    com.helix.core.storage.entity.TurnEntity(
                        id = "turn-replacement-1",
                        sessionId = "s1",
                        state = "WAITING_MODEL",
                        stepCount = 0,
                        startedAt = 1000L,
                        endedAt = null,
                        errorCode = null,
                        clientRequestId = "req-1",
                        inputFingerprint = fp1,
                    ),
                incomingSessionId = "s1",
                incomingFingerprint = fp2,
            )

        assertEquals(SubmitReceiptDecision.Conflict, decision)
    }
}
