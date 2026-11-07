package com.helix.app.infrastructure.conversation

import com.helix.app.R
import com.helix.app.application.tools.ToolCallFact
import com.helix.app.application.tools.ToolPresentationFact
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.todo.TodoWriteTool
import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.presentation.ApprovalCardState
import com.helix.feature.conversation.presentation.ToolDisplayCall
import com.helix.feature.conversation.presentation.ToolDisplayEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Framework bindings and source-specific labels are mapped once before entering disposable UI state. */
internal fun ToolPresentationFact.toConversationDisplay(): ToolDisplayEvent =
    when (this) {
        is ToolPresentationFact.Prepared -> {
            ToolDisplayEvent.Prepared(call.display())
        }

        is ToolPresentationFact.Running -> {
            ToolDisplayEvent.Running(call.display())
        }

        is ToolPresentationFact.Settled -> {
            ToolDisplayEvent.Settled(
                call.display(),
                state,
                summary,
                durationMs,
                preDispatch,
                denialCode?.let(ApprovalUiMapper::codeLabel),
                ApprovalUiMapper.executionFailureLabel(origin, state == ToolCallState.NEEDS_REVIEW),
                R.string.tool_state_side_effect_pending,
                todoItems(),
            )
        }

        is ToolPresentationFact.ApprovalRequested -> {
            ToolDisplayEvent.ApprovalRequested(
                call.display(),
                ApprovalUiMapper.buildCard(
                    approvalId = approvalId,
                    binding = request.binding,
                    state = ApprovalCardState.PENDING,
                    descriptor = descriptor,
                    arguments = arguments,
                    profile = profile,
                    dataOrigin = dataOrigin,
                    egressOrigin = egress?.endpoint?.origin,
                    egressResidence = egress?.endpoint?.residence()?.name,
                    egressCategory = egress?.dataSensitivity,
                    boundedRule = ApprovalUiMapper.boundedRuleUi(request.boundedEgressRule),
                    confirmationDetail = request.confirmationDetail,
                    terminalDetail = null,
                ),
            )
        }

        is ToolPresentationFact.ApprovalDecided -> {
            ToolDisplayEvent.ApprovalDecided(approvalId, decision, R.string.approval_terminal_user_denied)
        }

        is ToolPresentationFact.ApprovalDecisionFailed -> {
            ToolDisplayEvent.ApprovalDecisionFailed(approvalId, R.string.approval_terminal_op_failed)
        }

        is ToolPresentationFact.CallFinished -> {
            ToolDisplayEvent.CallFinished(callId)
        }

        is ToolPresentationFact.TurnFinished -> {
            ToolDisplayEvent.TurnFinished(turnId)
        }
    }

private fun ToolCallFact.display() = ToolDisplayCall(sessionId, turnId, callId, name, arguments, modelIntent)

private fun ToolPresentationFact.Settled.todoItems() =
    if (!preDispatch && state == ToolCallState.COMPLETED && call.name == TodoWriteTool.NAME) {
        runCatching { Json.parseToJsonElement(call.arguments).jsonObject }
            .getOrNull()
            ?.let(TaskLedgerProjection::itemsFromArgs)
            .orEmpty()
    } else {
        emptyList()
    }
