package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.VisibleReplyFrame
import com.helix.core.model.TurnState
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.MessagePresentation
import com.helix.feature.conversation.presentation.ThinkingParser
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
            ThinkingParser.parse(final?.content.orEmpty()).text
        } else {
            turn.streamingText.orEmpty()
        },
        phase,
        turn.modelStep,
    )
}

@Composable
@Suppress("FunctionName")
internal fun ObserveLiveReplyReadAloud(
    service: ReadAloudService?,
    frame: VisibleReplyFrame?,
) {
    LaunchedEffect(service, frame) {
        val playing = service?.livePlaybackTurnId
        val invalid =
            frame == null || frame.speechId != service?.state?.value?.messageId ||
                frame.phase in setOf(VisibleReplyFrame.Phase.WAITING, VisibleReplyFrame.Phase.CANCELLED)
        if (playing != null && invalid) service.stop()
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
