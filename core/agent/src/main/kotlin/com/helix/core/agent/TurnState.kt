package com.helix.core.agent

import com.helix.core.model.ApprovalId
import com.helix.core.model.CorrelationId
import com.helix.core.model.HelixError
import com.helix.core.model.ModelCallId
import com.helix.core.model.SessionId
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState as TurnPhase

/**
 * Token accounting for one model call of a turn.
 *
 * Known values reported by the provider replace the conservative byte estimate for that
 * dimension; unknown values always keep an estimate, never zero (doc 02 section 5.3).
 */
data class CallTokenAccount(
    val callId: ModelCallId,
    val requestBytes: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val responseBytes: Long = 0,
) {
    init {
        requireNonNegativeFields(requestBytes, responseBytes, inputTokens, outputTokens, totalTokens)
    }

    /** Reported input tokens, or the conservative estimate of the request bytes. */
    val effectiveInput: Long
        get() = inputTokens ?: TokenEstimator.estimateTokens(requestBytes)

    /** Reported output tokens, or the conservative estimate of the received bytes. */
    val effectiveOutput: Long
        get() = outputTokens ?: TokenEstimator.estimateTokens(responseBytes)

    /**
     * Reported total, or the sum of the effective input/output. A reported total is always
     * trusted as-is even when it differs from the derived sum.
     */
    val effectiveTotal: Long
        get() = totalTokens ?: effectiveInput + effectiveOutput

    private fun requireNonNegativeFields(
        request: Long,
        response: Long,
        input: Long?,
        output: Long?,
        total: Long?,
    ) {
        val values = listOf(request, response, input, output, total)
        require(values.none { it != null && it < 0 }) { "token accounting values must be >= 0" }
    }
}

/** A tool call of the current model response, processed serially (first version). */
data class PendingToolCall(
    val toolCallId: ToolCallId,
    val toolName: ToolName,
    val toolVersion: ToolVersion,
    val requiresApproval: Boolean,
    val approvalId: ApprovalId? = null,
    val state: ToolCallState,
) {
    init {
        if (approvalId != null) {
            require(state != ToolCallState.PENDING) { "an approval proof cannot be bound to a PENDING call" }
        }
    }

    /** Applies a legal [ToolCallState] transition; rejects illegal ones. */
    fun withState(next: ToolCallState): PendingToolCall {
        require(state.canTransitionTo(next)) { "illegal tool call transition $state -> $next" }
        return copy(state = next)
    }
}

/** A recorded tool outcome for the audit trail and the context rebuild. */
data class RecordedToolOutcome(
    val toolCallId: ToolCallId,
    val outcome: ToolOutcome,
)

/**
 * Full reducer state of one agent turn: the turn phase (core:model [TurnPhase]) plus the
 * data the [TurnReducer] needs to enforce budgets, process the serial tool queue and track
 * interrupted side effects.
 *
 * Invariants (enforced by the reducer, not by the constructor):
 * - terminal phase => [error]/[finishReason] consistent with the phase;
 * - non-terminal phase => no [error] and no [finishReason];
 * - [pendingCalls] non-empty only while a model response is being processed;
 * - [committedCallId] and [activeCallId] are never set at the same time.
 */
data class TurnState(
    val sessionId: SessionId,
    val turnId: TurnId,
    val correlationId: CorrelationId,
    val phase: TurnPhase,
    val budgets: TurnBudgets,
    val step: Int = 0,
    val modelCalls: Int = 0,
    val committedCallId: ModelCallId? = null,
    val activeCallId: ModelCallId? = null,
    val callAccounts: List<CallTokenAccount> = emptyList(),
    val pendingCalls: List<PendingToolCall> = emptyList(),
    val recordedOutcomes: List<RecordedToolOutcome> = emptyList(),
    val deniedCalls: List<ToolCallId> = emptyList(),
    val uncertainToolCallId: ToolCallId? = null,
    val error: HelixError? = null,
    val finishReason: String? = null,
) {
    /**
     * Cumulative tokens used so far (known values plus conservative estimates for missing
     * usage). This is the number checked against `maxTotalTokens`.
     */
    val usedTotalTokens: Long
        get() = callAccounts.sumOf { it.effectiveTotal }

    fun isTerminal(): Boolean = phase.isTerminal

    companion object {
        fun initial(
            sessionId: SessionId,
            turnId: TurnId,
            correlationId: CorrelationId,
            budgets: TurnBudgets,
        ): TurnState =
            TurnState(
                sessionId = sessionId,
                turnId = turnId,
                correlationId = correlationId,
                phase = TurnPhase.CREATED,
                budgets = budgets,
            )
    }
}
