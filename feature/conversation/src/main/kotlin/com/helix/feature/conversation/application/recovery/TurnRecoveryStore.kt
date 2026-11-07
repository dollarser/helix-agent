package com.helix.feature.conversation.application.recovery

import com.helix.core.agent.RecoveryInspectionSnapshot

/** Atomic claim and terminal commands; no Room entities, mutable runtime snapshots, or replay permission. */
interface TurnRecoveryStore {
    fun claim(
        turnId: String,
        at: Long,
    ): TurnRecoveryClaim

    fun hasSuccessor(parentTurnId: String): Boolean

    /** Closes the original task plus its inspection when applicable, including notice/audit/Goal. */
    fun finish(
        turnId: String,
        reason: String,
        inspectionFailed: Boolean,
    )
}

sealed interface TurnRecoveryClaim {
    data object Unavailable : TurnRecoveryClaim

    data object AlreadyClaimed : TurnRecoveryClaim

    data class InspectionFinished(
        val failed: Boolean,
    ) : TurnRecoveryClaim

    data class Claimed(
        val snapshot: RecoveryInspectionSnapshot?,
    ) : TurnRecoveryClaim
}
