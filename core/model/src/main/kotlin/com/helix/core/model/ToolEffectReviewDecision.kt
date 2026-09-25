package com.helix.core.model

/**
 * Immutable user review decision for an uncertain external tool effect (ADR-AGENT-001 section 3).
 *
 * Parked tool calls with effect uncertainty ([ToolCallState.NEEDS_REVIEW] during live execution
 * or [ToolCallState.INTERRUPTED] after process death) are resolved explicitly by user action.
 * A review never overwrites the raw executor outcome or status on [ToolCallState]; instead,
 * it is persisted as an independent fact in `tool_call_reviews`.
 */
enum class ToolEffectReviewDecision {
    /** The user confirmed that the external side effect was applied. */
    CONFIRMED_APPLIED,

    /** The user confirmed that the external side effect was not applied. */
    CONFIRMED_NOT_APPLIED,

    /** The user acknowledged that the external side effect remains unknown; the turn will be cancelled. */
    ACKNOWLEDGED_UNKNOWN,
}
