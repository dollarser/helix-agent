package com.helix.core.agent

import com.helix.core.model.GoalBudgets

/** New-Goal defaults only. Per-request model/window limits remain resolved from live metadata. */
object GoalBudgetDefaults {
    val VALUE = GoalBudgets(512, 2_048, 32_000_000, 28_800_000, 7_200_000, 0)

    fun validate(budgets: GoalBudgets): GoalBudgets = budgets.also { require(it.maxGoalRounds >= 1) }
}
