package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessagePresentation
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.VisibleReplyFrame
import com.helix.core.model.TurnState
import kotlinx.coroutines.delay

/** Uses exactly the visible text projection; tool timeline and reasoning are never inputs. */
internal fun visibleReplyFrame(screen: ChatScreenState): VisibleReplyFrame? {
    val session = screen.openSessionId
    val turn = screen.activeTurn
    if (session == null || turn == null) return null
    val final =
        screen.messages.lastOrNull {
            it.turnId == turn.id && it.role == "assistant" && it.presentation == MessagePresentation.NORMAL
        }
    val phase =
        when (turn.state) {
            TurnState.RECEIVING_MODEL -> {
                VisibleReplyFrame.Phase.STREAMING
            }

            TurnState.COMPLETED -> {
                if (final == null) VisibleReplyFrame.Phase.FINALIZING else VisibleReplyFrame.Phase.COMPLETED
            }

            TurnState.CANCELLING, TurnState.CANCELLED, TurnState.FAILED,
            TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW,
            -> {
                VisibleReplyFrame.Phase.CANCELLED
            }

            else -> {
                VisibleReplyFrame.Phase.WAITING
            }
        }
    return VisibleReplyFrame(
        session,
        turn.id,
        if (turn.state == TurnState.COMPLETED) {
            final?.content.orEmpty()
        } else {
            turn.streamingText.orEmpty()
        },
        phase,
    )
}

@Composable
@Suppress("FunctionName")
internal fun ObserveLiveReplyReadAloud(
    service: ReadAloudService?,
    frame: VisibleReplyFrame?,
) {
    LaunchedEffect(service, frame) {
        if (frame == null) {
            if (service?.liveReply?.activeId != null) service.stop()
        } else {
            service?.liveReply?.update(frame)
            if (frame.phase == VisibleReplyFrame.Phase.FINALIZING) {
                // Terminal state can arrive before persisted message projection. Never guess the final text.
                delay(5000)
                if (service?.liveReply?.activeId == frame.speechId) service.stop()
            }
        }
    }
}
