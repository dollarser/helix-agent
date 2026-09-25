package com.helix.app.review

import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision

/**
 * UI representation of an uncertain tool call requiring human effect review (ADR-AGENT-001).
 */
data class ReviewItemUi(
    val toolCallId: String,
    val turnId: String,
    val callId: String,
    val toolName: String,
    val argsJson: String,
    val state: ToolCallState,
    val existingDecision: ToolEffectReviewDecision?,
    val reviewedAt: Long?,
)

/**
 * Aggregated review status for a parked Turn (ADR-AGENT-001 section 4).
 *
 * Review never resumes the old Turn. Once every uncertain call has an immutable decision, the
 * old execution attempt can close as INTERRUPTED and any continuation starts a successor Turn.
 * [ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN] is a durable acknowledgement of uncertainty,
 * not an implicit task cancellation; any later effect is a fresh ToolCall under normal policy.
 */
data class TurnReviewStatus(
    val turnId: String,
    val items: List<ReviewItemUi>,
) {
    /** True if all uncertain tool calls have recorded human review decisions. */
    val isComplete: Boolean
        get() = items.isNotEmpty() && items.all { it.existingDecision != null }

    /** True if at least one call was acknowledged as unknown. */
    val hasAcknowledgedUnknown: Boolean
        get() = items.any { it.existingDecision == ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN }

    /** True when the parked attempt has enough immutable review facts to be closed. */
    val canResolve: Boolean
        get() = isComplete

    /** Number of items that still lack a review decision. */
    val pendingReviewCount: Int
        get() = items.count { it.existingDecision == null }
}

/**
 * Submission payload for a tool effect review decision.
 */
data class ToolReviewSubmission(
    val toolCallId: String,
    val decision: ToolEffectReviewDecision,
    val reviewedAt: Long? = null,
)

/**
 * Outcome of resolving human review for an old execution attempt. Review never resumes that Turn.
 */
sealed interface TurnReviewResolutionResult {
    data class Resolved(
        val turnId: String,
    ) : TurnReviewResolutionResult
}
