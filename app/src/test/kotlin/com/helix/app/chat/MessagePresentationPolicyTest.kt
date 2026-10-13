package com.helix.app.chat

import com.helix.core.model.TurnState
import com.helix.core.storage.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePresentationPolicyTest {
    @Test fun canonicalResponseDisplayCopyKeepsItsProgressPresentation() {
        val updated = history.map { if (it.id == "progress") it.copy(kind = "TOOL_PROGRESS") else it }
        assertEquals(
            MessagePresentation.PROGRESS,
            MessagePresentationPolicy.apply(updated, visible, mapOf("turn" to "RUNNING_TOOL"))[1].presentation,
        )
        assertEquals(
            MessagePresentation.COMPLETED_PROGRESS,
            MessagePresentationPolicy.apply(updated, visible, mapOf("turn" to "COMPLETED"))[1].presentation,
        )
        assertEquals(visible, MessagePresentationPolicy.apply(updated, visible, mapOf("turn" to "CANCELLED")))
    }

    private val history =
        listOf(
            row("user", "USER", "TEXT", 0),
            row("progress", "ASSISTANT", "TEXT", 1),
            row("calls", "ASSISTANT", "TOOL_CALLS", 2),
            row("result", "TOOL", "TOOL_RESULT", 3),
            row("answer", "ASSISTANT", "TEXT", 4),
        )
    private val visible =
        listOf(
            MessageUi("user", "user", "Start", "turn", 0),
            MessageUi("progress", "assistant", "Checking", "turn", 1),
            MessageUi("answer", "assistant", "Done", "turn", 4),
        )

    @Test fun completedProgressIsFoldableOnlyBesideAPersistedVisibleAnswer() {
        assertEquals(
            listOf(MessagePresentation.NORMAL, MessagePresentation.COMPLETED_PROGRESS, MessagePresentation.NORMAL),
            apply(TurnState.COMPLETED).map { it.presentation },
        )
        assertEquals(
            MessagePresentation.PROGRESS,
            MessagePresentationPolicy.apply(history, visible.dropLast(1), mapOf("turn" to "COMPLETED"))[1].presentation,
        )
    }

    @Test fun liveProgressNeverFoldsAndUnknownStreamingMessagesStayNormal() {
        assertEquals(MessagePresentation.PROGRESS, apply(TurnState.RUNNING_TOOL)[1].presentation)
        assertEquals(MessagePresentation.NORMAL, MessageUi("streaming", "assistant", "Please choose").presentation)
    }

    @Test fun failureStopReviewAndCorruptStatesDoNotMuteOrFoldText() {
        listOf("FAILED", "CANCELLED", "CANCELLING", "INTERRUPTED", "NEEDS_REVIEW", "bad").forEach { state ->
            assertEquals(visible, MessagePresentationPolicy.apply(history, visible, mapOf("turn" to state)))
        }
        assertEquals(visible, MessagePresentationPolicy.apply(history, visible, emptyMap()))
    }

    @Test fun userQuestionsAndRecoveryNoticesRemainIndependent() {
        val question = row("question", "ASSISTANT", UserQuestionService.KIND, 5)
        assertEquals(
            visible,
            MessagePresentationPolicy.apply(history + question, visible, mapOf("turn" to "COMPLETED")),
        )
        val notice = MessageUi("notice", "system", "Review required", "turn", 5)
        assertEquals(
            notice,
            MessagePresentationPolicy.apply(history, visible + notice, mapOf("turn" to "COMPLETED")).last(),
        )
    }

    @Test fun ambiguousCrossTurnOrSupersededBoundariesRemainNormal() {
        listOf(
            history.map { if (it.id == "calls") it.copy(turnId = "other") else it },
            history.map { if (it.id == "calls") it.copy(supersededBy = "retry") else it },
            history.filterNot { it.id == "calls" },
        ).forEach { rows ->
            assertEquals(visible, MessagePresentationPolicy.apply(rows, visible, mapOf("turn" to "COMPLETED")))
        }
    }

    @Test fun reloadSortsByDurableSequenceAndRetainsOriginalTextAndIdentity() {
        val first = apply(TurnState.COMPLETED)
        assertEquals(first, MessagePresentationPolicy.apply(history.reversed(), visible, mapOf("turn" to "COMPLETED")))
        assertEquals(visible.map { it.content }, first.map { it.content })
        assertEquals(visible.map { it.id to it.sequence }, first.map { it.id to it.sequence })
    }

    private fun apply(state: TurnState) = MessagePresentationPolicy.apply(history, visible, mapOf("turn" to state.name))

    private fun row(
        id: String,
        role: String,
        kind: String,
        sequence: Long,
    ) = MessageEntity(id, "session", "turn", role, kind, null, sequence)
}
