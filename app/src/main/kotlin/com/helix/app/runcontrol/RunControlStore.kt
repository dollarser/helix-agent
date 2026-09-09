package com.helix.app.runcontrol

import com.helix.app.internal.LineStore
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-owned, process-recoverable mode and Turn budget configuration (HXA-099). */
data class RunControlConfig(
    val mode: AgentMode,
    val chatToolsEnabled: Boolean,
    val budgets: TurnBudgets,
    val reasoning: ReasoningEffort = ReasoningEffort.OFF,
)

/**
 * Product bounds for a single phone Turn. These caps are independent of Standard/Advanced:
 * neither a profile nor the UI can widen them. Provider limits are intersected at request time.
 */
object TurnBudgetBounds {
    const val MAX_STEPS = 32
    const val MAX_MODEL_CALLS = 16
    const val MAX_INPUT_TOKENS = 1_000_000L
    const val MAX_OUTPUT_TOKENS = 128_000L
    const val MAX_TOTAL_TOKENS = 1_000_000L

    val DEFAULT = TurnBudgets(8, 9, 128_000, 4_096, 160_000)

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

interface RunControlStore {
    val flow: StateFlow<RunControlConfig>
    val current: RunControlConfig

    fun setMode(mode: AgentMode)

    fun setReasoning(reasoning: ReasoningEffort)

    fun setChatToolsEnabled(enabled: Boolean)

    fun setBudgets(budgets: TurnBudgets)
}

class PersistedRunControlStore(
    private val store: LineStore,
) : RunControlStore {
    private val state = MutableStateFlow(readStored())

    override val flow: StateFlow<RunControlConfig> = state.asStateFlow()
    override val current: RunControlConfig get() = state.value

    override fun setReasoning(reasoning: ReasoningEffort) = update(state.value.copy(reasoning = reasoning))

    override fun setMode(mode: AgentMode) = update(state.value.copy(mode = mode))

    override fun setChatToolsEnabled(enabled: Boolean) = update(state.value.copy(chatToolsEnabled = enabled))

    override fun setBudgets(budgets: TurnBudgets) =
        update(state.value.copy(budgets = TurnBudgetBounds.validate(budgets)))

    private fun update(next: RunControlConfig) {
        store.setLines(
            KEY,
            listOf(
                next.mode.name,
                next.chatToolsEnabled.toString(),
                next.budgets.toStorageString(),
                next.reasoning.name,
            ),
        )
        state.value = next
    }

    @Suppress("SwallowedException") // corrupt UI state fails closed to bounded defaults
    private fun readStored(): RunControlConfig =
        try {
            val lines = store.lines(KEY)
            require(lines.size in 3..4)
            val enabled = lines[1].toBooleanStrict()
            RunControlConfig(
                AgentMode.valueOf(lines[0]),
                enabled,
                TurnBudgetBounds.validate(TurnBudgets.parse(lines[2])),
                lines.getOrNull(3)?.let(ReasoningEffort::valueOf) ?: ReasoningEffort.OFF,
            )
        } catch (_: IllegalArgumentException) {
            RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT)
        }

    private companion object {
        const val KEY = "run_control_v1"
    }
}
