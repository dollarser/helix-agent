package com.helix.feature.conversation.presentation

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.presentation.ApprovalCardState
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ToolTimelinePosition
import kotlinx.coroutines.flow.MutableStateFlow
import com.helix.feature.conversation.R as ConversationR

/** Disposable chat projection. It never dispatches, settles, or grants an approval. */
class ChatToolPresentation(
    private val screen: MutableStateFlow<ChatScreenState>,
    private val strings: (Int, Array<out Any>) -> String,
    private val changed: () -> Unit,
    private val positionForCall: (String, String) -> ToolTimelinePosition?,
) {
    private val timeline = ChatToolTimeline(screen, strings)
    private val nativeCards = NativeApprovalProjection()

    fun nativeApprovals(turnId: String) = nativeCards.forTurn(turnId)

    fun publish(fact: ToolDisplayEvent) {
        when (fact) {
            is ToolDisplayEvent.Prepared -> {
                row(fact.call, str(ConversationR.string.tool_state_processing), position = true)
            }

            is ToolDisplayEvent.Running -> {
                changed()
                row(fact.call, str(ConversationR.string.tool_state_running), typedState = ToolCallState.RUNNING)
            }

            is ToolDisplayEvent.Settled -> {
                settled(fact)
            }

            is ToolDisplayEvent.ApprovalRequested -> {
                approvalRequested(fact)
            }

            is ToolDisplayEvent.ApprovalDecided -> {
                approvalDecided(fact)
            }

            is ToolDisplayEvent.ApprovalDecisionFailed -> {
                approvalDecisionFailed(fact)
            }

            is ToolDisplayEvent.CallFinished -> {
                nativeCards.finishCall(fact.callId)
                changed()
            }

            is ToolDisplayEvent.TurnFinished -> {
                nativeCards.finishTurn(fact.turnId)
            }
        }
    }

    private fun approvalDecided(fact: ToolDisplayEvent.ApprovalDecided) {
        nativeCards.remove(fact.approvalId)
        changed()
        timeline.updateCard(fact.approvalId) {
            when {
                it.state == ApprovalCardState.SUCCEEDED -> {
                    it
                }

                fact.decision == ApprovalDecision.APPROVED && it.state != ApprovalCardState.PENDING -> {
                    it
                }

                fact.decision == ApprovalDecision.APPROVED -> {
                    it.copy(state = ApprovalCardState.APPROVED)
                }

                else -> {
                    it.copy(
                        state = ApprovalCardState.DENIED,
                        terminalDetail = str(fact.deniedLabel),
                    )
                }
            }
        }
    }

    private fun approvalDecisionFailed(fact: ToolDisplayEvent.ApprovalDecisionFailed) {
        nativeCards.remove(fact.approvalId)
        changed()
        timeline.updateCard(fact.approvalId) {
            if (it.state == ApprovalCardState.PENDING) {
                it.copy(
                    state = ApprovalCardState.FAILED,
                    terminalDetail = str(fact.failureLabel),
                )
            } else {
                it
            }
        }
    }

    private fun approvalRequested(fact: ToolDisplayEvent.ApprovalRequested) {
        val card = fact.card
        nativeCards.register(fact.call.turnId, fact.callId, card)
        changed()
        if (screen.value.openSessionId != fact.call.sessionId) return
        if (timeline.hasCall(fact.callId)) {
            timeline.attachCardToRow(fact.callId, card)
        } else {
            timeline.publishToolRow(
                fact.call.sessionId,
                fact.call.turnId,
                fact.callId,
                fact.call.name,
                fact.call.arguments,
                str(ConversationR.string.tool_state_awaiting_approval),
                null,
                card,
                state = ToolCallState.AWAITING_APPROVAL,
            )
        }
    }

    @Suppress("LongMethod") // Typed terminal outcomes retain distinct approval and ledger projections.
    private fun settled(fact: ToolDisplayEvent.Settled) {
        if (screen.value.openSessionId != fact.call.sessionId) return
        if (fact.preDispatch) {
            val label =
                if (fact.state ==
                    ToolCallState.DENIED
                ) {
                    ConversationR.string.tool_state_denied
                } else {
                    ConversationR.string.tool_state_failed
                }
            row(fact.call, str(label), fact.summary, fact.durationMs, typedState = fact.state)
            return
        }
        when (fact.state) {
            ToolCallState.COMPLETED -> {
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.SUCCEEDED, null)
                row(
                    fact.call,
                    str(ConversationR.string.tool_state_completed),
                    fact.summary,
                    fact.durationMs,
                    typedState = fact.state,
                )
                if (fact.ledger.isNotEmpty()) timeline.publishLedger(fact.call.sessionId, fact.ledger)
            }

            ToolCallState.DENIED -> {
                val detail = str(requireNotNull(fact.denialLabel))
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.FAILED, detail, keepDenied = true)
                row(fact.call, detail, detail, fact.durationMs, typedState = fact.state)
            }

            ToolCallState.CANCELLED -> {
                timeline.setCardStateForCall(
                    fact.callId,
                    ApprovalCardState.FAILED,
                    str(ConversationR.string.turn_stopped),
                )
                row(
                    fact.call,
                    str(ConversationR.string.tool_state_cancelled),
                    str(ConversationR.string.tool_summary_cancelled_before_start),
                    fact.durationMs,
                    typedState = fact.state,
                )
            }

            ToolCallState.FAILED, ToolCallState.NEEDS_REVIEW -> {
                val review = fact.state == ToolCallState.NEEDS_REVIEW
                val detail = str(requireNotNull(fact.failureLabel))
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.FAILED, detail)
                row(
                    fact.call,
                    str(if (review) requireNotNull(fact.reviewLabel) else ConversationR.string.tool_state_failed),
                    detail,
                    fact.durationMs,
                    typedState = fact.state,
                )
            }

            else -> {
                error("Non-terminal tool outcome cannot be projected as settled: ${fact.state}")
            }
        }
    }

    private fun row(
        call: ToolDisplayCall,
        state: String,
        summary: String? = null,
        duration: Long? = null,
        position: Boolean = false,
        typedState: ToolCallState = ToolCallState.PENDING,
    ) {
        if (screen.value.openSessionId != call.sessionId) return
        timeline.publishToolRow(
            call.sessionId,
            call.turnId,
            call.callId,
            call.name,
            call.arguments,
            state,
            summary,
            null,
            duration,
            modelIntent = call.modelIntent,
            state = typedState,
            position = if (position) positionForCall(call.sessionId, call.callId) else null,
        )
    }

    private fun str(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)
}
