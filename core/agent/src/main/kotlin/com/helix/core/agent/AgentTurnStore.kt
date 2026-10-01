package com.helix.core.agent

/** A domain commit, not a callback that exposes a database transaction to Core. */
data class TerminalCommitCommand(
    val checkpoint: TurnTerminalCheckpoint,
    val outcome: ModelStreamTerminal,
) {
    init {
        require(outcome.state.isTerminal) { "terminal outcome required" }
        require(checkpoint.sessionId.isNotBlank() && checkpoint.turnId.isNotBlank())
        require(checkpoint.modelCallId.isNotBlank() && checkpoint.modelStep >= 1)
    }
}

sealed interface TerminalCommitResult {
    data class Applied(
        val outcome: ModelStreamTerminal,
    ) : TerminalCommitResult

    data class AlreadyApplied(
        val outcome: ModelStreamTerminal,
    ) : TerminalCommitResult

    data class Conflict(
        val reason: String,
    ) : TerminalCommitResult

    data class Unavailable(
        val reason: String,
    ) : TerminalCommitResult
}

/**
 * Narrow durable domain port. Implementations atomically settle text, Turn, ModelCall and GoalRun.
 * A returned success proves the transaction committed; infrastructure failures propagate and must
 * never be converted to an Applied receipt. Cancellation does not roll back external tool effects.
 */
fun interface AgentTurnStore {
    suspend fun commitTerminal(command: TerminalCommitCommand): TerminalCommitResult
}

class TurnCommitRejectedException(
    val code: String,
) : IllegalStateException(code)

fun TerminalCommitResult.requireOutcome(): ModelStreamTerminal =
    when (this) {
        is TerminalCommitResult.Applied -> outcome
        is TerminalCommitResult.AlreadyApplied -> outcome
        is TerminalCommitResult.Conflict -> throw TurnCommitRejectedException(reason)
        is TerminalCommitResult.Unavailable -> throw TurnCommitRejectedException(reason)
    }
