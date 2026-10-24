package com.helix.app.infrastructure.agent

import com.helix.core.model.TurnBudgets
import com.helix.core.storage.entity.TurnEntity

internal object AutomaticRecoveryPolicy {
    fun requestId(parent: String) = "auto-recovery:$parent"

    fun isInspection(turn: TurnEntity): Boolean =
        turn.recoveryFromTurnId?.let { turn.clientRequestId == requestId(it) } == true

    fun eligible(turn: TurnEntity): Boolean =
        turn.state in setOf("NEEDS_REVIEW", "INTERRUPTED") && turn.pauseRequestedAt == null &&
            turn.errorCode != "USER_STOP" && !isInspection(turn)

    fun limits(snapshot: TurnRuntimeSnapshot): TurnBudgets? = snapshot.control.budgets
}
