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
        durationMs: Long? = null,
    ) {
        when (outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                settleSucceeded(row, toolCallId, toolName, outcome, durationMs)
            }

            is ToolDispatchOutcome.Denied -> {
                settleDenied(row, toolCallId, toolName, outcome, durationMs)
            }

            ToolDispatchOutcome.Cancelled -> {
                settleCancelled(row, toolCallId, toolName, durationMs)
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                settleExecutionFailed(row, toolCallId, toolName, outcome, sideEffectUnknown, durationMs)
            }
        }
    }

    private fun settleSucceeded(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.Succeeded,
        durationMs: Long? = null,
    ) {
        val summary = boundedSummary(outcome.result.payload)
        ToolSettlementWriter(storage, clock, idGenerator).persist(
            row,
            ToolCallState.COMPLETED,
            "SUCCEEDED",
            summary,
            outcome.result.payload,
            verified = true,
        )
        timeline.setCardStateForCall(toolCallId, ApprovalCardState.SUCCEEDED, null)
        timeline.publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(R.string.tool_state_completed),
            summary,
            null,
            durationMs,
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
        durationMs: Long? = null,
    ) {
        val userDetail = str(ApprovalUiMapper.codeLabel(outcome.code))
        ToolSettlementWriter(storage, clock, idGenerator).persist(
            row,
            ToolCallState.DENIED,
            "DENIED",
            outcome.detail,
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
            durationMs,
        )
    }

    private fun settleCancelled(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        durationMs: Long? = null,
    ) {
        ToolSettlementWriter(storage, clock, idGenerator).persist(
            row,
            ToolCallState.CANCELLED,
            "CANCELLED",
            str(R.string.tool_summary_cancelled_before_start),
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
            durationMs,
        )
    }

    private fun settleExecutionFailed(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.ExecutionFailed,
        sideEffectUnknown: Boolean,
        durationMs: Long? = null,
    ) {
        val state = if (sideEffectUnknown) ToolCallState.NEEDS_REVIEW else ToolCallState.FAILED
        val userDetail =
            str(
                ApprovalUiMapper.executionFailureLabel(
                    originFor(toolCallId),
                    requiresReview = sideEffectUnknown,
                ),
            )
        ToolSettlementWriter(storage, clock, idGenerator).persist(row, state, state.name, outcome.detail)
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
        timeline.publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, label, userDetail, null, durationMs)
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
        storage.withTransaction {
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
            GoalToolCallBudget(storage, clock).finish(toolCallId)
        }
        timeline.publishToolRow(
            turn.id,
            toolCallId,
            toolNameRaw,
            rawArgs,
            str(R.string.tool_state_denied),
            detail,
            null,
        )
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
