package com.helix.core.agent

/** Application facts only: presentation translates these reasons; they grant no capability. */
enum class TurnSubmissionBlock {
    SESSION_UNAVAILABLE,
    PROVIDER_CHANGED,
    SESSION_BUSY,
    RECEIPT_CONFLICT,
    INPUT_CONFIGURATION_CHANGED,
    INPUT_NO_LONGER_CONSUMABLE,
    CONTINUATION_DEFERRED,
    CONTINUATION_REFUSED,
    INVALID_HISTORY_MUTATION,
    FRESH_GUARD_REJECTED,
    GOAL_REFUSED,
    RECOVERY_UNAVAILABLE,
}

sealed interface TurnSubmissionResult {
    data class Started(
        val turnId: String,
        val deduplicated: Boolean = false,
    ) : TurnSubmissionResult

    data class Blocked(
        val reason: TurnSubmissionBlock,
    ) : TurnSubmissionResult
}

fun interface TurnSubmissionCommands {
    suspend fun submit(command: SubmitTurnCommand): TurnSubmissionResult
}

fun TurnSubmissionResult.turnIdOrNull(): String? = (this as? TurnSubmissionResult.Started)?.turnId

class TurnStartBlocked(
    val sessionId: String,
    val reason: TurnSubmissionBlock,
) : RuntimeException("Turn submission refused: $reason")
