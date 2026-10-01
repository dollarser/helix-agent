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
)

/**
 * Product bounds for a single phone Turn. These caps are independent of Standard/Advanced:
 * neither a profile nor the UI can widen them. Provider limits are intersected at request time.
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
            require(it.maxSteps <= MAX_STEPS) { "maxSteps exceeds product cap" }
            require(it.maxModelCalls <= MAX_MODEL_CALLS) { "maxModelCalls exceeds product cap" }
            require(it.maxInputTokens in 1..MAX_INPUT_TOKENS) { "maxInputTokens is outside product bounds" }
            require(it.maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "maxOutputTokens is outside product bounds" }
            require(it.maxTotalTokens in 1..MAX_TOTAL_TOKENS) { "maxTotalTokens is outside product bounds" }
            require(it.maxOutputTokens <= it.maxTotalTokens) { "maxOutputTokens exceeds maxTotalTokens" }
        }
}
