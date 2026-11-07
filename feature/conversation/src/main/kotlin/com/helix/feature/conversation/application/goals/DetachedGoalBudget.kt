package com.helix.feature.conversation.application.goals

/** A detached Job borrows its original Goal clock; it never creates a new run or approval. */
interface DetachedGoalBudget {
    fun prepare(
        sessionId: String,
        turnId: String,
        executionId: String,
        requestedMillis: Long,
    ): Long

    /** The original launcher proved that the Job never started. */
    fun reject(
        sessionId: String,
        turnId: String,
        executionId: String,
    )

    /** The caller verified the original Job terminal and finished its result effects. */
    fun settle(
        sessionId: String,
        turnId: String,
        executionId: String,
        terminalElapsedMs: Long?,
    )
}
