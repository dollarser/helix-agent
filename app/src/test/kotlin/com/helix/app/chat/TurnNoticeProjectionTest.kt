package com.helix.app.chat

import com.helix.app.application.agent.TurnInterruptionNotice
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnNoticeProjectionTest {
    @Test fun recoveryStaysAtLastOriginalReplyAcrossNewMessagesAndProjectionRecreation() {
        val original =
            listOf(
                MessageUi("question", "user", "Original", "old", 0),
                MessageUi("progress", "assistant", "Working", "old", 1),
                MessageUi("answer", "assistant", "Partial result", "old", 2),
            )
        val notice = mapOf("old" to TurnInterruptionNotice.UNKNOWN_EFFECTS)
        val first = attachTurnNotices(original, notice)
        val later =
            (1..3).flatMap { n ->
                listOf(
                    MessageUi("u$n", "user", "Next request", "next$n", n * 2L + 2),
                    MessageUi("a$n", "assistant", "Next reply", "next$n", n * 2L + 3),
                )
            }
        val refreshed = attachTurnNotices(first + later, notice)
        assertEquals(listOf("answer"), refreshed.filter { it.interruptionNotice != null }.map { it.id })
        assertEquals(refreshed, attachTurnNotices(original + later, notice))
        assertEquals("old", refreshed.single { it.interruptionNotice != null }.turnId)
        // The detail target is the same persisted Turn, never the new reply or inspection.
        assertEquals(
            "old",
            conversationEntries(
                ChatScreenState(emptyList(), "session", null, refreshed, emptyList(), null, null, null, null),
            ).single {
                it.messages.any { message -> message.interruptionNotice != null }
            }.key,
        )
    }

    @Test fun noReplyUsesOriginalQuestionAndOffPageNoticeNeverMovesToANewerTurn() {
        val original = MessageUi("original", "user", "Original", "old", 0)
        val next = MessageUi("next", "user", "Next", "new", 1)
        val notice = mapOf("old" to TurnInterruptionNotice.INSPECTION_UNAVAILABLE)
        assertEquals(
            "original",
            attachTurnNotices(listOf(original, next), notice)
                .single {
                    it.interruptionNotice != null
                }.id,
        )
        assertNull(attachTurnNotices(listOf(next), notice).single().interruptionNotice)
        assertNull(
            attachTurnNotices(
                listOf(original.copy(interruptionNotice = TurnInterruptionNotice.UNKNOWN_EFFECTS)),
                emptyMap(),
            ).single().interruptionNotice,
        )
    }

    @Test fun existingTurnFactsAreEnoughAndRecoveryChildrenNeverOwnWarnings() {
        assertEquals(
            TurnInterruptionNotice.INTERRUPTED,
            TurnInterruptionNotice.forDisplay(null, "INTERRUPTED", false, false, false),
        )
        assertEquals(
            TurnInterruptionNotice.UNKNOWN_EFFECTS,
            TurnInterruptionNotice.forDisplay(null, "NEEDS_REVIEW", false, true, false),
        )
        assertEquals(
            TurnInterruptionNotice.INTERRUPTED,
            TurnInterruptionNotice.forDisplay(
                TurnInterruptionNotice.UNKNOWN_EFFECTS,
                "INTERRUPTED",
                false,
                false,
                false,
            ),
        )
        assertNull(TurnInterruptionNotice.forDisplay(null, "FAILED", false, false, false))
        assertNull(
            TurnInterruptionNotice.forDisplay(TurnInterruptionNotice.UNKNOWN_EFFECTS, "CANCELLED", true, true, false),
        )
        assertNull(
            TurnInterruptionNotice.forDisplay(
                TurnInterruptionNotice.INSPECTION_UNAVAILABLE,
                "FAILED",
                false,
                false,
                true,
            ),
        )
    }

    @Test fun stopAndKnownResultsDoNotBecomeUnknown() {
        assertNull(TurnInterruptionNotice.classify(true, true, true))
        assertEquals(TurnInterruptionNotice.INTERRUPTED, TurnInterruptionNotice.classify(false, false, false))
        assertEquals(TurnInterruptionNotice.INSPECTION_UNAVAILABLE, TurnInterruptionNotice.classify(false, false, true))
        assertEquals(TurnInterruptionNotice.UNKNOWN_EFFECTS, TurnInterruptionNotice.classify(false, true, true))
        // An ended original attempt never blocks a later task on its state alone.
        assertEquals(true, TurnState.INTERRUPTED.isTerminal)
    }
}
