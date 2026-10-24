package com.helix.core.agent.runtime

/** Narrow process-local execution-handle port. Durable Turn truth remains in the lifecycle store. */
interface TurnExecutionHandles {
    fun cancelSignal(turnId: String): TurnCancelSignal?

    fun goalTime(turnId: String): GoalDeadlineControl?

    fun installGoalTime(
        turnId: String,
        timer: GoalDeadlineControl,
    )

    fun clearGoalTime(
        turnId: String,
        timer: GoalDeadlineControl,
    )
}
