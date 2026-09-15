package com.helix.core.agent

import com.helix.core.model.ArtifactRef

/**
 * Outcome of a single Act's final state (HX2-06, research doc section 5.2 "Completion
 * Contract").
 */
enum class CompletionStatus {
    COMPLETE,
    PARTIAL,
    BLOCKED,
}

/**
 * The Act completion contract (HX2-06, research doc section 5.2). A side-effecting Act must end
 * with this report; when it does not, the UI shows "the model stopped, but the task is not
 * confirmed complete" rather than a green Done. The model emits it as a final structured output
 * or a `turn.report`; the harness validates the *shape* and the UI renders the status.
 *
 * Pure value — the *truth* of the report (did the work actually happen?) is the host's job to
 * verify, not the contract's. This is the same split as Goal completion: the model reports, the
 * host gates.
 */
data class CompletionReport(
    val status: CompletionStatus,
    val summary: String,
    val artifacts: List<ArtifactRef> = emptyList(),
    val verification: List<String> = emptyList(),
    val remaining: List<String> = emptyList(),
) {
    init {
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY) {
            "summary must be 1..$MAX_SUMMARY non-blank characters"
        }
        require(artifacts.size <= MAX_LIST) { "artifacts must be <= $MAX_LIST" }
        require(verification.size <= MAX_LIST) { "verification must be <= $MAX_LIST" }
        require(remaining.size <= MAX_LIST) { "remaining must be <= $MAX_LIST" }
        // A complete task has, by definition, nothing left to do.
        if (status == CompletionStatus.COMPLETE) {
            require(remaining.isEmpty()) { "a COMPLETE report must not list remaining work" }
        }
        // A blocked task must name what blocks it, otherwise it is indistinguishable from
        // partial.
        if (status == CompletionStatus.BLOCKED) {
            require(remaining.isNotEmpty()) { "a BLOCKED report must name what blocks it" }
        }
    }

    val isComplete: Boolean
        get() = status == CompletionStatus.COMPLETE

    companion object {
        const val MAX_SUMMARY = 2048
        const val MAX_LIST = 32
    }
}
