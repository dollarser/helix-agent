package com.helix.app.application.tools

import android.util.Log
import com.helix.core.model.Clock
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.ToolCallOrigin
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.ToolOrigin

/** Only disposable projection work is isolated. Persistence failures must propagate. */
@Suppress("TooGenericExceptionCaught")
internal fun publishToolProjection(
    callId: String,
    publish: () -> Unit,
) {
    try {
        publish()
    } catch (failure: Exception) {
        Log.e("HelixTools", "Tool projection failed for $callId; durable outcome is unchanged", failure)
    }
}

internal enum class PreDispatchDenialKind(
    val state: ToolCallState,
    val decisionSource: DecisionSource,
) {
    FRAMEWORK_REJECTED(ToolCallState.FAILED, DecisionSource.FRAMEWORK),
    RECOVERY_REVIEW_REQUIRED(ToolCallState.DENIED, DecisionSource.POLICY),
}

/** Existing single settlement writer commits before any disposable display event. */
internal class ToolSettlement(
    private val storage: HelixStorage,
    private val audit: AuditSink,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val presentation: ToolPresentationSink,
    private val originFor: (String) -> ToolOrigin?,
) {
    fun settleToolCall(
        row: ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome,
        sideEffectUnknown: Boolean = false,
        durationMs: Long? = null,
    ) {
        val state: ToolCallState
        val status: String
        val summary: String
        var content: String? = null
        var verified = false
        var denialCode: DispatchOutcomeCode? = null
        when (outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                state = ToolCallState.COMPLETED
                status = "SUCCEEDED"
                summary = boundedSummary(outcome.result.payload)
                content = outcome.result.payload
                verified = true
            }

            is ToolDispatchOutcome.Denied -> {
                state = ToolCallState.DENIED
                status = "DENIED"
                summary = outcome.detail
                denialCode = outcome.code
            }

            ToolDispatchOutcome.Cancelled -> {
                state = ToolCallState.CANCELLED
                status = "CANCELLED"
                summary = "Cancelled before execution began."
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                state = if (sideEffectUnknown) ToolCallState.NEEDS_REVIEW else ToolCallState.FAILED
                status = state.name
                summary = boundedSummary(outcome.detail)
                content = ToolFailureContent.encode(outcome)
            }
        }
        ToolSettlementWriter(storage, clock, idGenerator).persist(row, state, status, summary, content, verified)
        publishToolProjection(toolCallId) {
            presentation.publish(
                ToolPresentationFact.Settled(
                    call = callFact(row, toolCallId, toolName),
                    state = state,
                    summary = summary,
                    durationMs = durationMs,
                    denialCode = denialCode,
                    origin = originFor(toolCallId),
                ),
            )
        }
    }

    /** A malformed call never enters Dispatcher; row/result/audit/budget still commit atomically. */
    @Suppress("LongParameterList", "LongMethod")
    fun persistPreDispatchDenied(
        turn: TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgs: String,
        version: String,
        code: DispatchOutcomeCode,
        detail: String,
        kind: PreDispatchDenialKind,
        modelIntent: String? = null,
        origin: ToolCallOrigin? = null,
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
                state = kind.state.name,
                modelIntent = modelIntent,
                origin = origin,
            )
            storage.toolResults.append(
                id = idGenerator(),
                toolCallId = toolCallId,
                status = kind.state.name,
                summary = detail,
                content = null,
            )
            audit.record(
                DispatchAuditEvent(
                    correlationId = toolCallId,
                    turnId = turn.id,
                    sessionId = turn.sessionId,
                    toolName = toolNameRaw,
                    toolVersion = version,
                    code = code,
                    decisionSource = kind.decisionSource,
                    operationClass = null,
                    bindingHash = null,
                    actionFingerprint = null,
                    outputHash = null,
                    outputTruncated = false,
                    startedAt = startedAt,
                    policyDecidedAt = finishedAt,
                    approvalAcquiredAt = null,
                    executionStartedAt = null,
                    finishedAt = finishedAt,
                ),
            )
            GoalToolCallBudget(storage, clock).finish(toolCallId)
        }
        publishToolProjection(toolCallId) {
            presentation.publish(
                ToolPresentationFact.Settled(
                    ToolCallFact(turn.sessionId, turn.id, toolCallId, toolNameRaw, rawArgs, modelIntent),
                    kind.state,
                    detail,
                    null,
                    denialCode = code,
                    preDispatch = true,
                ),
            )
        }
        return ToolDispatchOutcome.Denied(code, detail)
    }

    private fun callFact(
        row: ToolCallEntity,
        callId: String,
        name: String,
    ) = ToolCallFact(
        storage.turns.resolve(row.turnId).sessionId,
        row.turnId,
        callId,
        name,
        row.argsJson,
        row.modelIntent,
    )

    private fun boundedSummary(payload: String): String =
        if (payload.length <=
            500
        ) {
            payload
        } else {
            payload.take(500) + "…"
        }
}
