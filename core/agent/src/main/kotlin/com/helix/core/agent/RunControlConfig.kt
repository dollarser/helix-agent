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

/**
 * Request output bounds and historical snapshot templates shared by both channels.
 * Legacy cumulative fields/constants are storage compatibility values, not execution limits.
 */
object TurnBudgetBounds {
    const val MAX_STEPS = 10_000
    const val MAX_MODEL_CALLS = 20_000
    const val MAX_INPUT_TOKENS = 1_000_000L
    const val MAX_OUTPUT_TOKENS = 128_000L
    const val MAX_TOTAL_TOKENS = 1_000_000_000L

    val DEFAULT = TurnBudgets(512, 1_024, 1_000_000, 16_384, 32_000_000)
    val PREVIOUS_DEFAULT = TurnBudgets(32, 33, 128_000, 4_096, 160_000)
    val LEGACY_DEFAULT = TurnBudgets(8, 9, 128_000, 4_096, 160_000)

    fun validate(value: TurnBudgets): TurnBudgets =
        value.also {
            require(it.maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "maxOutputTokens is outside product bounds" }
        }
}
