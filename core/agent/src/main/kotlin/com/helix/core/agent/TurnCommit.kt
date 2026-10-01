package com.helix.core.agent

import com.helix.core.model.TurnState

/** Durable identity expected by one terminal commit; contains no storage or UI types. */
data class TurnCommitExpectation(
    val sessionId: String,
    val turnId: String,
    val phase: TurnState,
    val modelCallId: String,
    val modelStep: Int,
    val modelCallClosed: Boolean,
)

/** A transaction-local projection, not a second writable representation of a Turn. */
data class StoredTurnCommitState(
    val sessionId: String,
    val turnId: String,
    val phase: TurnState,
    val modelStep: Int,
    val modelCallTurnId: String?,
    val modelCallState: String?,
    val errorCode: String?,
    val hasOtherRunningModelCall: Boolean = false,
    val modelCallId: String? = null,
)

sealed interface TurnCommitDecision {
    data object Apply : TurnCommitDecision

    data class AlreadyApplied(
        val phase: TurnState,
        val errorCode: String?,
    ) : TurnCommitDecision

    data class Conflict(
        val reason: String,
    ) : TurnCommitDecision

    data class Unavailable(
        val reason: String,
    ) : TurnCommitDecision
}

/** Shared deterministic admission for the real transaction and in-memory contract fixtures. */
object TurnCommitPolicy {
    /** A durable user stop wins over a late model outcome, without erasing its error facts. */
    fun outcome(
        outcome: ModelStreamTerminal,
        phase: TurnState,
    ): ModelStreamTerminal =
        if (phase == TurnState.CANCELLING && outcome.state != TurnState.CANCELLED) {
            outcome.copy(state = TurnState.CANCELLED)
        } else {
            outcome
        }

    @Suppress("ReturnCount") // Each rejected identity has a stable, independently tested reason.
    fun terminal(
        expected: TurnCommitExpectation,
        actual: StoredTurnCommitState?,
    ): TurnCommitDecision {
        if (actual == null) return TurnCommitDecision.Unavailable("TURN_NOT_FOUND")
        if (expected.turnId != actual.turnId || expected.sessionId != actual.sessionId) {
            return TurnCommitDecision.Conflict("TURN_SESSION_MISMATCH")
        }
        if (actual.modelCallTurnId == null) return TurnCommitDecision.Unavailable("MODEL_CALL_NOT_FOUND")
        if (actual.modelCallId != expected.modelCallId) return TurnCommitDecision.Conflict("MODEL_CALL_ID_MISMATCH")
        if (actual.modelCallTurnId != expected.turnId) {
            return TurnCommitDecision.Conflict("MODEL_CALL_TURN_MISMATCH")
        }
        if (actual.phase.isTerminal) return TurnCommitDecision.AlreadyApplied(actual.phase, actual.errorCode)
        if (expected.modelStep != actual.modelStep) return TurnCommitDecision.Conflict("TURN_STEP_CONFLICT")
        if (actual.phase != expected.phase && actual.phase != TurnState.CANCELLING) {
            return TurnCommitDecision.Conflict("TURN_PHASE_CONFLICT")
        }
        return modelCallState(expected, actual)
    }

    private fun modelCallState(
        expected: TurnCommitExpectation,
        actual: StoredTurnCommitState,
    ): TurnCommitDecision =
        when {
            actual.hasOtherRunningModelCall -> {
                TurnCommitDecision.Conflict("MODEL_CALL_OWNER_CONFLICT")
            }

            !expected.modelCallClosed && actual.modelCallState != "RUNNING" -> {
                TurnCommitDecision.Conflict("MODEL_CALL_CLOSED")
            }

            expected.modelCallClosed &&
                actual.modelCallState !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED") -> {
                TurnCommitDecision.Conflict("MODEL_CALL_NOT_CLOSED")
            }

            else -> {
                TurnCommitDecision.Apply
            }
        }
}
