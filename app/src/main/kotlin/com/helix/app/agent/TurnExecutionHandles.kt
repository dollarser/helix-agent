package com.helix.app.agent

/** Narrow process-local execution-handle port. Durable Turn truth remains in Room/TurnEngine. */
internal interface TurnExecutionHandles {
    fun cancelSignal(turnId: String): TurnCancelSignal?

    fun goalTime(turnId: String): GoalTimeBudget?

    fun installGoalTime(
        turnId: String,
        timer: GoalTimeBudget,
    )

    fun clearGoalTime(
        turnId: String,
        timer: GoalTimeBudget,
    )
}
