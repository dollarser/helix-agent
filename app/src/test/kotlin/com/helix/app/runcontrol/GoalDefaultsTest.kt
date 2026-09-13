package com.helix.app.runcontrol

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoalDefaultsTest {
    @Test fun defaultsWorkWithoutAnySavedTurnSettings() {
        val lines = InMemoryLineStore()
        val first = PersistedRunControlStore(lines)
        val custom = GoalBudgetDefaults.VALUE.copy(maxTotalTokens = 7_000_000)
        first.setGoalBudgets(custom)
        assertEquals(custom, PersistedRunControlStore(lines).current.goalBudgets)
        first.setMode(AgentMode.GOAL)
        assertEquals(custom, PersistedRunControlStore(lines).current.goalBudgets)
    }

    @Test fun corruptGoalPreferencesDoNotResetModeOrTurnBudget() {
        val lines = InMemoryLineStore()
        val first = PersistedRunControlStore(lines)
        first.setMode(AgentMode.ACT)
        val custom = first.current.budgets.copy(maxSteps = 12)
        first.setBudgets(custom)
        lines.setLines("goal_defaults_v1", listOf("invalid"))
        val restored = PersistedRunControlStore(lines).current
        assertEquals(GoalBudgetDefaults.VALUE, restored.goalBudgets)
        assertEquals(AgentMode.ACT, restored.mode)
        assertEquals(custom, restored.budgets)
    }

    @Test fun invalidNewGoalDefaultsDoNotOverwritePreviousValues() {
        val lines = InMemoryLineStore()
        val first = PersistedRunControlStore(lines)
        val custom = GoalBudgetDefaults.VALUE.copy(maxModelCalls = 80)
        first.setGoalBudgets(custom)
        listOf(
            custom.copy(maxTotalTokens = 0),
            custom.copy(maxWakeDurationMillis = 0),
            custom.copy(maxDurationMillis = 1),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { first.setGoalBudgets(invalid) }
            assertEquals(custom, PersistedRunControlStore(lines).current.goalBudgets)
        }
    }

    @Test fun resetDoesNotAffectTurnSettingsOrMode() {
        val first = PersistedRunControlStore(InMemoryLineStore())
        first.setMode(AgentMode.GOAL)
        first.setGoalBudgets(GoalBudgetDefaults.VALUE.copy(maxModelCalls = 80))
        first.setGoalBudgets(GoalBudgetDefaults.VALUE)
        assertEquals(AgentMode.GOAL, first.current.mode)
        assertEquals(TurnBudgetBounds.DEFAULT, first.current.budgets)
        assertEquals(GoalBudgetDefaults.VALUE, first.current.goalBudgets)
    }
}
