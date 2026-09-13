package com.helix.app.chat

import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.todo.TodoWriteTool
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.Clock
import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Persists each scheduled outcome and projects its verified or uncertain state without replay. */
@Suppress("LongParameterList")
internal class ChatToolSettlement(
    private val storage: HelixStorage,
    private val toolPipeline: ToolPipeline,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val strings: (Int, Array<out Any>) -> String,
    private val timeline: ChatToolTimeline,
    private val originFor: (String) -> com.helix.tools.framework.ToolOrigin?,
) {
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    fun settleToolCall(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome,
        sideEffectUnknown: Boolean = false,
    ) {
        when (outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                settleSucceeded(row, toolCallId, toolName, outcome)
            }

            is ToolDispatchOutcome.Denied -> {
                settleDenied(row, toolCallId, toolName, outcome)
            }

            ToolDispatchOutcome.Cancelled -> {
                settleCancelled(row, toolCallId, toolName)
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                settleExecutionFailed(row, toolCallId, toolName, outcome, sideEffectUnknown)
            }
        }
        GoalToolCallBudget(storage, clock).finish(toolCallId)
    }

    private fun settleSucceeded(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.Succeeded,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.COMPLETED)
        val summary = boundedSummary(outcome.result.payload)
        val result =
            storage.toolResults.append(
                id = idGenerator(),
                toolCallId = toolCallId,
                status = "SUCCEEDED",
                summary = summary,
                content = outcome.result.payload,
            )
        storage.toolResults.markVerified(result)
        timeline.setCardStateForCall(toolCallId, ApprovalCardState.SUCCEEDED, null)
        timeline.publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(R.string.tool_state_completed),
            summary,
            null,
        )
        publishLedgerForTodoWrite(row)
    }

    /**
     * HX2-07: a verified `todo.write` settle updates the open session's Progress card live —
     * the same atomic, session-scoped publish the timeline rows use. Any parse problem keeps
     * the previous ledger (a corrupt row must not wipe the user's view of progress).
     */
    private fun publishLedgerForTodoWrite(row: com.helix.core.storage.entity.ToolCallEntity) {
        if (row.name != TodoWriteTool.NAME) return
        val args = runCatching { Json.parseToJsonElement(row.argsJson).jsonObject }.getOrNull() ?: return
        val items = TaskLedgerProjection.itemsFromArgs(args)
        if (items.isNotEmpty()) {
            timeline.publishLedger(storage.turns.resolve(row.turnId).sessionId, items)
        }
    }

    private fun settleDenied(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.Denied,
    ) {
        val userDetail = str(ApprovalUiMapper.codeLabel(outcome.code))
        storage.toolCalls.updateState(row, ToolCallState.DENIED)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "DENIED",
            summary = outcome.detail,
            content = null,
        )
        timeline.setCardStateForCall(
            toolCallId,
            ApprovalCardState.FAILED,
            userDetail,
            keepDenied = true,
        )
        timeline.publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(ApprovalUiMapper.codeLabel(outcome.code)),
            userDetail,
            null,
        )
    }

    private fun settleCancelled(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.CANCELLED)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "CANCELLED",
            summary = str(R.string.tool_summary_cancelled_before_start),
            content = null,
        )
        timeline.setCardStateForCall(toolCallId, ApprovalCardState.FAILED, str(R.string.turn_stopped))
        timeline.publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(R.string.tool_state_cancelled),
            str(R.string.tool_summary_cancelled_before_start),
            null,
        )
    }

    private fun settleExecutionFailed(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.ExecutionFailed,
        sideEffectUnknown: Boolean,
    ) {
        val state = if (sideEffectUnknown) ToolCallState.NEEDS_REVIEW else ToolCallState.FAILED
        val userDetail =
            str(
                ApprovalUiMapper.executionFailureLabel(
                    originFor(toolCallId),
                    requiresReview = sideEffectUnknown,
                ),
            )
        storage.toolCalls.updateState(row, state)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = state.name,
            summary = outcome.detail,
            content = null,
        )
        timeline.setCardStateForCall(
            toolCallId,
            ApprovalCardState.FAILED,
            userDetail,
        )
        val label =
            if (sideEffectUnknown) {
                str(R.string.tool_state_side_effect_pending)
            } else {
                str(R.string.tool_state_failed)
            }
        timeline.publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, label, userDetail, null)
    }

    /**
     * A model tool call that is malformed BEFORE the dispatcher can run it (an invalid
     * tool name, non-object arguments): persist the call row + the failed result + ONE
     * audit event (the dispatcher's own per-dispatch audit contract, emitted here because
     * the dispatcher never sees these calls; its correlationId is the tool call id — the
     * same per-call correlation the dispatcher's own audit events use), show the rejection
     * in the timeline, and return the stable typed rejection.
     */
    fun persistRejectedToolCall(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgs: String,
        version: String,
        code: DispatchOutcomeCode,
        detail: String,
    ): ToolDispatchOutcome.Denied {
        val startedAt = clock.now().toEpochMilli()
        val finishedAt = clock.now().toEpochMilli()
        storage.toolCalls.append(
            id = toolCallId,
            turnId = turn.id,
            callId = toolCallId,
            name = toolNameRaw,
            version = version,
            argsJson = rawArgs,
            state = ToolCallState.FAILED.name,
        )
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "FAILED",
            summary = detail,
            content = null,
        )
        toolPipeline.auditSink.record(
            DispatchAuditEvent(
                correlationId = toolCallId,
                turnId = turn.id,
                sessionId = turn.sessionId,
                toolName = toolNameRaw,
                toolVersion = version,
                code = code,
                decisionSource = DecisionSource.FRAMEWORK,
                riskLevel = null,
                bindingHash = null,
                actionFingerprint = null,
                outputHash = null,
                outputTruncated = false,
                startedAt = startedAt,
                policyDecidedAt = null,
                approvalAcquiredAt = null,
                executionStartedAt = null,
                finishedAt = finishedAt,
            ),
        )
        timeline.publishToolRow(
            turn.id,
            toolCallId,
            toolNameRaw,
            rawArgs,
            str(R.string.tool_state_denied),
            detail,
            null,
        )
        GoalToolCallBudget(storage, clock).finish(toolCallId)
        return ToolDispatchOutcome.Denied(code, detail)
    }

    private fun boundedSummary(payload: String): String {
        if (payload.length <= SUMMARY_CAP) return payload
        return payload.take(SUMMARY_CAP) + "…"
    }

    private companion object {
        const val SUMMARY_CAP = 500
    }
}
