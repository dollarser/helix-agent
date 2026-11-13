package com.helix.app.application.tools

import android.util.Log
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.tools.framework.DecisionSource
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
    private val store: ToolSettlementStore,
    private val presentation: ToolPresentationSink,
    private val originFor: (String) -> ToolOrigin?,
) {
    fun settleToolCall(
        rowId: String,
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
        store.persist(ToolSettlementCommand(rowId, state, status, summary, content, verified))
        publishToolProjection(toolCallId) {
            presentation.publish(
                ToolPresentationFact.Settled(
                    call = store.callFact(rowId, toolCallId, toolName),
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
        turnId: String,
        toolCallId: String,
        toolNameRaw: String,
        rawArgs: String,
        version: String,
        code: DispatchOutcomeCode,
        detail: String,
        kind: PreDispatchDenialKind,
        modelIntent: String? = null,
        origin: ToolSettlementOrigin? = null,
    ): ToolDispatchOutcome.Denied {
        store.persistDenied(
            PreDispatchDenialCommand(
                turnId,
                toolCallId,
                toolNameRaw,
                rawArgs,
                version,
                code,
                detail,
                kind,
                modelIntent,
                origin,
            ),
        )
        publishToolProjection(toolCallId) {
            presentation.publish(
                ToolPresentationFact.Settled(
                    store.callFact(toolCallId, toolCallId, toolNameRaw),
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

    private fun boundedSummary(payload: String): String =
        if (payload.length <=
            500
        ) {
            payload
        } else {
            payload.take(500) + "…"
        }
}
