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

    /** Attempt cleanup stays separate; only its original task owns the user notice. */
    fun noticeOwner(
        turn: TurnEntity,
        resolve: (String) -> TurnEntity,
    ): TurnEntity {
        if (!isInspection(turn)) return turn
        val original = resolve(requireNotNull(turn.recoveryFromTurnId))
        require(original.id == turn.recoveryFromTurnId && original.sessionId == turn.sessionId) {
            "Recovery notice owner mismatch"
        }
        require(!isInspection(original)) { "Recovery inspection cannot own another inspection" }
        return original
    }

    fun limits(snapshot: TurnRuntimeSnapshot): TurnBudgets? = snapshot.control.budgets
}
