package com.helix.app.chat

import com.helix.app.R
import com.helix.app.application.tools.ToolCallFact
import com.helix.app.application.tools.ToolPresentationFact
import com.helix.app.application.tools.ToolPresentationSink
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.todo.TodoWriteTool
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ToolCallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Disposable chat projection. It never dispatches, settles, or grants an approval. */
internal class ChatToolPresentation(
    private val screen: MutableStateFlow<ChatScreenState>,
    private val strings: (Int, Array<out Any>) -> String,
    private val changed: () -> Unit,
    private val positionForCall: (String, String) -> ToolTimelinePosition?,
) : ToolPresentationSink {
    private val timeline = ChatToolTimeline(screen, strings)
    private val nativeCards = NativeApprovalProjection()

    fun nativeApprovals(turnId: String) = nativeCards.forTurn(turnId)

    override fun publish(fact: ToolPresentationFact) {
        when (fact) {
            is ToolPresentationFact.Prepared -> {
                row(fact.call, str(R.string.tool_state_processing), position = true)
            }

            is ToolPresentationFact.Running -> {
                changed()
                row(fact.call, str(R.string.tool_state_running))
            }

            is ToolPresentationFact.Settled -> {
                settled(fact)
            }

            is ToolPresentationFact.ApprovalRequested -> {
                approvalRequested(fact)
            }

            is ToolPresentationFact.ApprovalDecided -> {
                approvalDecided(fact)
            }

            is ToolPresentationFact.ApprovalDecisionFailed -> {
                approvalDecisionFailed(fact)
            }

            is ToolPresentationFact.CallFinished -> {
                nativeCards.finishCall(fact.callId)
                changed()
            }

            is ToolPresentationFact.TurnFinished -> {
                nativeCards.finishTurn(fact.turnId)
            }
        }
    }

    private fun approvalDecided(fact: ToolPresentationFact.ApprovalDecided) {
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
                        terminalDetail = str(R.string.approval_terminal_user_denied),
                    )
                }
            }
        }
    }

    private fun approvalDecisionFailed(fact: ToolPresentationFact.ApprovalDecisionFailed) {
        nativeCards.remove(fact.approvalId)
        changed()
        timeline.updateCard(fact.approvalId) {
            if (it.state == ApprovalCardState.PENDING) {
                it.copy(
                    state = ApprovalCardState.FAILED,
                    terminalDetail = str(R.string.approval_terminal_op_failed),
                )
            } else {
                it
            }
        }
    }

    private fun approvalRequested(fact: ToolPresentationFact.ApprovalRequested) {
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = fact.approvalId,
                binding = fact.request.binding,
                state = ApprovalCardState.PENDING,
                descriptor = fact.descriptor,
                arguments = fact.arguments,
                profile = fact.profile,
                dataOrigin = fact.dataOrigin,
                egressOrigin = fact.egress?.endpoint?.origin,
                egressResidence =
                    fact.egress
                        ?.endpoint
                        ?.residence()
                        ?.name,
                egressCategory = fact.egress?.dataSensitivity,
                boundedRule = ApprovalUiMapper.boundedRuleUi(fact.request.boundedEgressRule),
                confirmationDetail = fact.request.confirmationDetail,
                terminalDetail = null,
            )
        nativeCards.register(fact.call.turnId, fact.callId, card)
        changed()
        if (screen.value.openSessionId != fact.call.sessionId) return
        if (timeline.hasCall(fact.callId)) {
            timeline.attachCardToRow(fact.callId, card)
        } else {
            timeline.publishToolRow(
                fact.call.turnId,
                fact.callId,
                fact.call.name,
                fact.call.arguments,
                str(R.string.tool_state_awaiting_approval),
                null,
                card,
            )
        }
    }

    private fun settled(fact: ToolPresentationFact.Settled) {
        if (screen.value.openSessionId != fact.call.sessionId) return
        if (fact.preDispatch) {
            val label =
                if (fact.state ==
                    ToolCallState.DENIED
                ) {
                    R.string.tool_state_denied
                } else {
                    R.string.tool_state_failed
                }
            row(fact.call, str(label), fact.summary, fact.durationMs)
            return
        }
        when (fact.state) {
            ToolCallState.COMPLETED -> {
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.SUCCEEDED, null)
                row(fact.call, str(R.string.tool_state_completed), fact.summary, fact.durationMs)
                if (fact.call.name == TodoWriteTool.NAME) {
                    val args = runCatching { Json.parseToJsonElement(fact.call.arguments).jsonObject }.getOrNull()
                    val items = args?.let(TaskLedgerProjection::itemsFromArgs).orEmpty()
                    if (items.isNotEmpty()) timeline.publishLedger(fact.call.sessionId, items)
                }
            }

            ToolCallState.DENIED -> {
                val detail = str(ApprovalUiMapper.codeLabel(requireNotNull(fact.denialCode)))
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.FAILED, detail, keepDenied = true)
                row(fact.call, detail, detail, fact.durationMs)
            }

            ToolCallState.CANCELLED -> {
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.FAILED, str(R.string.turn_stopped))
                row(
                    fact.call,
                    str(R.string.tool_state_cancelled),
                    str(R.string.tool_summary_cancelled_before_start),
                    fact.durationMs,
                )
            }

            ToolCallState.FAILED, ToolCallState.NEEDS_REVIEW -> {
                val review = fact.state == ToolCallState.NEEDS_REVIEW
                val detail = str(ApprovalUiMapper.executionFailureLabel(fact.origin, requiresReview = review))
                timeline.setCardStateForCall(fact.callId, ApprovalCardState.FAILED, detail)
                row(
                    fact.call,
                    str(if (review) R.string.tool_state_side_effect_pending else R.string.tool_state_failed),
                    detail,
                    fact.durationMs,
                )
            }

            else -> {
                error("Non-terminal tool outcome cannot be projected as settled: ${fact.state}")
            }
        }
    }

    private fun row(
        call: ToolCallFact,
        state: String,
        summary: String? = null,
        duration: Long? = null,
        position: Boolean = false,
    ) {
        if (screen.value.openSessionId != call.sessionId) return
        timeline.publishToolRow(
            call.turnId,
            call.callId,
            call.name,
            call.arguments,
            state,
            summary,
            null,
            duration,
            modelIntent = call.modelIntent,
            position = if (position) positionForCall(call.sessionId, call.callId) else null,
        )
    }

    private fun str(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)
}
