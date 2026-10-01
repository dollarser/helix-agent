package com.helix.app.runcontrol

import com.helix.app.internal.LineStore
import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RunControlStore {
    val flow: StateFlow<RunControlConfig>
    val current: RunControlConfig

    fun setMode(mode: AgentMode)

    fun setReasoning(reasoning: ReasoningEffort)

    fun setChatToolsEnabled(enabled: Boolean)

    fun setBudgets(budgets: TurnBudgets)

    fun setGoalBudgets(budgets: GoalBudgets)
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

    override fun setGoalBudgets(budgets: GoalBudgets) {
        store.setLines("goal_defaults_v1", listOf(GoalBudgetDefaults.validate(budgets).toStorageString()))
        state.value = state.value.copy(goalBudgets = budgets)
    }

    private fun update(next: RunControlConfig) {
        store.setLines(
            KEY,
            listOf(
                next.mode.name,
                next.chatToolsEnabled.toString(),
                next.budgets.toStorageString(),
                next.reasoning.name,
                "budgets_v3",
            ),
        )
        state.value = next
    }

    @Suppress("SwallowedException") // corrupt UI state fails closed to bounded defaults
    private fun readStored(): RunControlConfig =
        try {
            val lines = store.lines(KEY)
            require(lines.size in 3..5)
            require(lines.size < 5 || lines[4] in setOf("budgets_v2", "budgets_v3"))
            val enabled = lines[1].toBooleanStrict()
            RunControlConfig(
                AgentMode.valueOf(lines[0]),
                enabled,
                TurnBudgetBounds.validate(TurnBudgets.parse(lines[2])).let {
                    when {
                        lines.size < 5 && it == TurnBudgetBounds.LEGACY_DEFAULT -> {
                            TurnBudgetBounds.DEFAULT
                        }

                        lines.getOrNull(4) != "budgets_v3" && it == TurnBudgetBounds.PREVIOUS_DEFAULT -> {
                            TurnBudgetBounds.DEFAULT
                        }

                        else -> {
                            it
                        }
                    }
                },
                lines.getOrNull(3)?.let(ReasoningEffort::valueOf) ?: ReasoningEffort.OFF,
                readGoalBudgets(),
            )
        } catch (_: IllegalArgumentException) {
            RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT, goalBudgets = readGoalBudgets())
        }

    @Suppress("SwallowedException") // Only malformed preferences fall back; saved Goals are never changed.
    private fun readGoalBudgets(): GoalBudgets =
        try {
            store.lines("goal_defaults_v1").singleOrNull()?.let { GoalBudgetDefaults.validate(GoalBudgets.parse(it)) }
                ?: GoalBudgetDefaults.VALUE
        } catch (_: IllegalArgumentException) {
            GoalBudgetDefaults.VALUE
        }

    private companion object {
        const val KEY = "run_control_v1"
    }
}
