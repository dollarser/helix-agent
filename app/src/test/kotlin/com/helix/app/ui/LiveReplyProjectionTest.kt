package com.helix.app.ui

import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessagePresentation
import com.helix.app.chat.MessageUi
import com.helix.app.chat.TurnUi
import com.helix.app.voice.VisibleReplyFrame
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveReplyProjectionTest {
    private fun screen(
        state: TurnState,
        messages: List<MessageUi> = emptyList(),
    ) = ChatScreenState(
        emptyList(),
        "session",
        null,
        messages,
        emptyList(),
        TurnUi("turn", state, "Visible answer only", null, false),
        null,
        null,
        null,
    )

    @Test fun streamingInputIsOnlyTheVisibleAssistantProjection() {
        val messages = listOf(MessageUi("tool", "tool", "tool JSON"), MessageUi("user", "user", "user text"))
        val result = requireNotNull(visibleReplyFrame(screen(TurnState.RECEIVING_MODEL, messages)))
        assertEquals("Visible answer only", result.text)
        assertEquals(VisibleReplyFrame.Phase.STREAMING, result.phase)
    }

    @Test fun finalIgnoresOtherTurnsToolsAndProgressMessages() {
        val messages =
            listOf(
                MessageUi("final", "assistant", "Final visible text", "turn"),
                MessageUi(
                    id = "progress",
                    role = "assistant",
                    content = "tool preface",
                    turnId = "turn",
                    presentation = MessagePresentation.PROGRESS,
                ),
                MessageUi("tool", "tool", "tool JSON", "turn"),
                MessageUi("other", "assistant", "other turn", "other"),
            )
        val result = requireNotNull(visibleReplyFrame(screen(TurnState.COMPLETED, messages)))
        assertEquals("Final visible text", result.text)
        assertEquals(VisibleReplyFrame.Phase.COMPLETED, result.phase)
    }

    @Test fun toolWaitCancelsAndMissingFinalIsNotGuessed() {
        val waiting = visibleReplyFrame(screen(TurnState.RUNNING_TOOL))
        val finalizing = visibleReplyFrame(screen(TurnState.COMPLETED))
        val cancelling = visibleReplyFrame(screen(TurnState.CANCELLING))
        assertEquals(VisibleReplyFrame.Phase.WAITING, waiting?.phase)
        assertEquals(VisibleReplyFrame.Phase.FINALIZING, finalizing?.phase)
        assertEquals(VisibleReplyFrame.Phase.CANCELLED, cancelling?.phase)
    }
}
