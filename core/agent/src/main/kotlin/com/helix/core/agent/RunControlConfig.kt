package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets

/** User-owned, process-recoverable mode and Turn budget configuration (HXA-099). */
data class RunControlConfig(
    val mode: AgentMode,
    val chatToolsEnabled: Boolean,
    val budgets: TurnBudgets,
    val reasoning: ReasoningEffort = ReasoningEffort.OFF,
    val goalBudgets: GoalBudgets = GoalBudgetDefaults.VALUE,
    val immediateMessages: Boolean = false,
)

/** Request output bounds shared by both channels. */
object TurnBudgetBounds {
    const val MAX_OUTPUT_TOKENS = 128_000L

    val DEFAULT = TurnBudgets(maxOutputTokens = 16_384)

    fun validate(value: TurnBudgets): TurnBudgets =
        value.also {
            require(it.maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "maxOutputTokens is outside product bounds" }
        }
}
