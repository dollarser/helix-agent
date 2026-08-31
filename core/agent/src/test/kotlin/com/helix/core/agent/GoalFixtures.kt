package com.helix.core.agent

import com.helix.core.model.CorrelationId
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.HelixError
import org.junit.Assert.fail

internal object GoalFixtures {
    val goal = GoalId("goal-1")
    val correlation = CorrelationId("corr-1")

    fun budgets(
        maxModelCalls: Int = 2,
        maxToolCalls: Int = 5,
        maxTotalTokens: Long = 100,
        maxDurationMillis: Long = 10_000,
        maxWakeDurationMillis: Long = 4_000,
        maxRetries: Int = 1,
    ): GoalBudgets =
        GoalBudgets(
            maxModelCalls,
            maxToolCalls,
            maxTotalTokens,
            maxDurationMillis,
            maxWakeDurationMillis,
            maxRetries,
        )

    fun criterion(
        id: String = "c1",
        description: String = "Login works",
    ): Criterion = Criterion(id, description)

    fun newGoal(budgets: GoalBudgets = budgets()): Goal =
        Goal.initial(goal, "Investigate the login flow", listOf(criterion()), budgets, correlation)

    fun error(
        message: String = "provider network failure",
        retryable: Boolean = false,
    ): HelixError = HelixError(ErrorCode.NETWORK, message, retryable, emptyMap(), correlation)
}

/** Applies [event] to a goal, failing the test if it is ignored. */
internal fun reduceGoal(
    state: Goal,
    event: GoalEvent,
): GoalStep {
    val step = GoalReducer.reduce(state, event)
    if (step.ignored) fail("goal event $event was ignored in phase ${state.state}")
    return step
}

/** Drives a fresh goal to RUNNING with the default budgets. */
internal fun runningGoal(budgets: GoalBudgets = GoalFixtures.budgets()): Goal {
    val ready = reduceGoal(GoalFixtures.newGoal(budgets), GoalEvent.Ready(null, null)).state
    return reduceGoal(ready, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).state
}
