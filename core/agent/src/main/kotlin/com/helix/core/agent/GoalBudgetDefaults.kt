package com.helix.core.agent

import com.helix.core.model.GoalBudgets

/** New-Goal defaults only. Per-request model/window limits remain resolved from live metadata. */
object GoalBudgetDefaults {
    val VALUE = GoalBudgets(maxGoalRounds = 256)

    fun validate(budgets: GoalBudgets): GoalBudgets = budgets.also { require(it.maxGoalRounds >= 1) }
}
