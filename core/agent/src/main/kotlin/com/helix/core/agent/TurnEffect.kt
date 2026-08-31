package com.helix.core.agent

import com.helix.core.model.HelixError
import com.helix.core.model.ModelCallId
import com.helix.core.model.ToolCallId

/**
 * Side effects the turn coordinator must perform after a [TurnReducer] step. Effects are the
 * only way the reducer influences the world: it never calls the context builder, provider or
 * tools itself. Effect payloads carry identifiers and bounded metadata only.
 */
sealed interface TurnEffect {
    /** Build the next model context from the persisted session/turn snapshot. */
    data object BuildContext : TurnEffect

    /**
     * Start the model call. `step` is the 1-based agent-loop step; `maxOutputTokens` is the
     * effective output cap for this call (the stricter of the budget's per-call output cap
     * and the remaining total-token headroom).
     */
    data class StartModelCall(
        val callId: ModelCallId,
        val step: Int,
        val maxOutputTokens: Long,
    ) : TurnEffect

    /** Present the pending tool call to the user (or approval surface) for a decision. */
    data class RequestApproval(
        val call: PendingToolCall,
    ) : TurnEffect

    /** Dispatch the tool call to its executor through the tool pipeline. */
    data class ExecuteToolCall(
        val call: PendingToolCall,
    ) : TurnEffect

    /** Persist the tool result (including denials) so the next context rebuild sees it. */
    data class RecordToolResult(
        val toolCallId: ToolCallId,
        val outcome: ToolOutcome,
    ) : TurnEffect

    /** Finalize the turn with a completion (final answer, refusal or cancellation). */
    data class CompleteTurn(
        val finishReason: String?,
    ) : TurnEffect

    /** Finalize the turn as failed with the given error. */
    data class FailTurn(
        val error: HelixError,
    ) : TurnEffect

    /** Cancel the in-flight model stream and/or tool execution. */
    data object CancelInFlight : TurnEffect
}
