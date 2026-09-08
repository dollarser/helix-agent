package com.helix.app.goal

/** Stable host facts only; raw exception text and private paths never become UI or audit reasons. */
internal enum class GoalEvidenceFailure {
    SOURCE_NOT_READY,
    UNSETTLED_CALLS,
    SOURCE_MISMATCH,
    CONTENT_CHANGED,
    TOO_LARGE,
    BINDING_CHANGED,
    UNSUPPORTED_SOURCE,
    READ_UNAVAILABLE,
    UNKNOWN,
}

internal class GoalEvidenceRejected(
    val reason: GoalEvidenceFailure,
    cause: Throwable? = null,
) : IllegalArgumentException(reason.name, cause)

internal fun requireGoalEvidence(
    condition: Boolean,
    reason: GoalEvidenceFailure,
) {
    if (!condition) throw GoalEvidenceRejected(reason)
}
