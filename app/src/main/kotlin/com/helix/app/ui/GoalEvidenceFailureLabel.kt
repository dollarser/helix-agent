package com.helix.app.ui

import com.helix.app.R
import com.helix.app.goal.GoalEvidenceFailure

internal fun goalEvidenceFailureLabel(failure: GoalEvidenceFailure?): Int =
    when (failure) {
        GoalEvidenceFailure.SOURCE_NOT_READY -> R.string.goal_evidence_not_ready
        GoalEvidenceFailure.UNSETTLED_CALLS -> R.string.goal_evidence_unsettled
        GoalEvidenceFailure.SOURCE_MISMATCH -> R.string.goal_evidence_wrong_source
        GoalEvidenceFailure.CONTENT_CHANGED -> R.string.goal_evidence_changed
        GoalEvidenceFailure.TOO_LARGE -> R.string.goal_evidence_too_large
        GoalEvidenceFailure.BINDING_CHANGED -> R.string.goal_evidence_binding_changed
        GoalEvidenceFailure.UNSUPPORTED_SOURCE -> R.string.goal_evidence_unsupported
        GoalEvidenceFailure.READ_UNAVAILABLE -> R.string.goal_evidence_read_unavailable
        GoalEvidenceFailure.UNKNOWN, null -> R.string.goal_evidence_unavailable
    }
