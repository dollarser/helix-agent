package com.helix.app.proot

import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState

/** Read-only reconciliation is available only after the owning execution has stopped. */
internal fun prootRecoveryEligible(
    turnState: String,
    callState: String,
): Boolean =
    when (turnState) {
        "INTERRUPTED" -> callState in setOf("INTERRUPTED", "NEEDS_REVIEW")
        "FAILED" -> callState == "NEEDS_REVIEW"
        TurnState.COMPLETED.name -> callState == ToolCallState.COMPLETED.name
        else -> false
    }
