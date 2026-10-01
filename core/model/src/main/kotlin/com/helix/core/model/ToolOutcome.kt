package com.helix.core.model

/**
 * Stable per-dispatch audit codes (roadmap HXA-035 "audit"; doc 11 采纳矩阵:
 * "记录 queue/approval/execution/verification 时间、decision source，不记录敏感正文").
 * Every terminal dispatch outcome maps to exactly one code; codes are stable identifiers
 * for the audit page (HXA-036) and for tests — never free text.
 */
enum class DispatchOutcomeCode {
    // before anything ran
    UNKNOWN_TOOL,
    NO_IMPLEMENTATION,
    INVALID_ARGUMENTS,
    BUDGET_EXHAUSTED,
    POLICY_DENIED,

    /**
     * A user DENY preference blocked the tool (HXA-200, ADR-0052). The legacy three-state
     * preference chain is removed (HXA-209 B4): this code is KEPT so that historical audit
     * rows keep their stable identifier — no new dispatch can produce it.
     */
    PREFERENCE_DENIED,

    /** The user's two-state tool availability disabled the tool (HXA-209, ADR-PERMISSIONS-001 section 1.1). */
    TOOL_DISABLED,

    /** The session permission resolver denied a DETERMINED classified operation (a DENY rule hit). */
    OPERATION_DENIED,

    /** The session permission resolver denied fail-closed: an UNDETERMINED effect touched a DENY rule. */
    OPERATION_DENIED_DOMAIN,

    SAME_TURN_DENIED,
    APPROVAL_PENDING,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED,
    APPROVAL_CONSUMED,
    APPROVAL_NOT_FOUND,
    CANCELLED_BEFORE_START,

    // execution started
    SUCCESS,
    TIMEOUT,
    CANCELLED_AFTER_START,
    TOOL_FAILED,
    INVALID_OUTPUT,
}

/**
 * The model-visible, bounded result of a successful dispatch: canonical output text
 * truncated to the descriptor's descriptor maxOutputBytes with the SHA-256 of the
 * FULL (pre-truncation) output preserved — a truncated result still proves which output it
 * was (security doc section 7.3: 超限截断并保留 hash/Artifact 引用).
 */
data class BoundToolResult(
    val payload: String,
    val outputHash: Sha256,
    val truncated: Boolean,
    val executionMillis: Long,
    val visualArtifact: com.helix.core.model.VisualArtifact? = null,
) {
    init {
        require(executionMillis >= 0) { "executionMillis must not be negative" }
    }
}

/** The terminal outcome of one dispatch; exactly one of the four. */
sealed interface ToolDispatchOutcome {
    /** The call executed to a model-visible, bounded, output-schema-valid result. */
    data class Succeeded(
        val result: BoundToolResult,
    ) : ToolDispatchOutcome

    /** Nothing executed: rejected before execution (validation, policy or approval). */
    data class Denied(
        val code: DispatchOutcomeCode,
        val detail: String,
    ) : ToolDispatchOutcome

    /** Cancelled before start: zero side effects, no proof consumed, nothing executed. */
    data object Cancelled : ToolDispatchOutcome

    /**
     * Execution started and ended non-successfully with a stable error code.
     * [sideEffectFree] is the executor's CONFIRMED report that this attempt produced no
     * side effect (doc 11 section 3.3) — the only case a bounded technical retry is
     * allowed. The framework trusts this flag only because it is set by the platform
     * executor (never by the model or MCP); when in doubt it must stay false.
     */
    data class ExecutionFailed(
        val code: DispatchOutcomeCode,
        val detail: String,
        val sideEffectFree: Boolean = false,
        val requiresReview: Boolean = false,
    ) : ToolDispatchOutcome {
        init {
            require(!sideEffectFree || !requiresReview) {
                "a confirmed side-effect-free failure cannot require side-effect review"
            }
        }
    }
}
