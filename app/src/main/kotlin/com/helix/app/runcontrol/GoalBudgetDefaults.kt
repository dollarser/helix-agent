package com.helix.app.runcontrol

import com.helix.core.model.GoalBudgets

/** New-Goal defaults only. Per-request model/window limits remain resolved from live metadata. */
object GoalBudgetDefaults {
    val VALUE = GoalBudgets(128, 256, 4_000_000, 7_200_000, 1_800_000, 0)

    fun validate(budgets: GoalBudgets): GoalBudgets =
        budgets.also {
            require(it.maxTotalTokens > 0 && it.maxDurationMillis > 0 && it.maxWakeDurationMillis > 0)
            require(it.maxWakeDurationMillis <= it.maxDurationMillis)
        }
}
