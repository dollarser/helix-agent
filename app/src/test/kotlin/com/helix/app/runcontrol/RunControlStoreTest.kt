package com.helix.app.runcontrol

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunControlStoreTest {
    @Test
    fun defaultsAreBoundedChatWithoutTools() {
        val store = PersistedRunControlStore(InMemoryLineStore())
        assertEquals(AgentMode.CHAT, store.current.mode)
        assertFalse(store.current.chatToolsEnabled)
        assertEquals(TurnBudgetBounds.DEFAULT, store.current.budgets)
    }

    @Test
    fun modeToolsAndBudgetsSurviveReconstruction() {
        val lines = InMemoryLineStore()
        val first = PersistedRunControlStore(lines)
        val budgets = TurnBudgets(4, 5, 32_000, 2_000, 34_000)
        first.setMode(AgentMode.GOAL)
        first.setChatToolsEnabled(true)
        first.setBudgets(budgets)

        assertEquals(RunControlConfig(AgentMode.GOAL, true, budgets), PersistedRunControlStore(lines).current)
    }

    @Test
    fun corruptPersistenceFailsClosedToDefaults() {
        val lines = InMemoryLineStore()
        lines.setLines("run_control_v1", listOf("ACT", "yes", "{}"))
        assertEquals(
            RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT),
            PersistedRunControlStore(lines).current,
        )
    }

    @Test
    fun rejectsEveryExtremeAndCrossFieldViolation() {
        val store = PersistedRunControlStore(InMemoryLineStore())
        assertFails { store.setBudgets(TurnBudgets(TurnBudgetBounds.MAX_STEPS + 1, 1, 1, 1, 1)) }
        assertFails { store.setBudgets(TurnBudgets(1, TurnBudgetBounds.MAX_MODEL_CALLS + 1, 1, 1, 1)) }
        assertFails { store.setBudgets(TurnBudgets(1, 1, TurnBudgetBounds.MAX_INPUT_TOKENS + 1, 1, 1)) }
        assertFails { store.setBudgets(TurnBudgets(1, 1, 1, TurnBudgetBounds.MAX_OUTPUT_TOKENS + 1, 200_000)) }
        assertFails { store.setBudgets(TurnBudgets(1, 1, 1, 1, TurnBudgetBounds.MAX_TOTAL_TOKENS + 1)) }
        assertFails { store.setBudgets(TurnBudgets(1, 1, 1, 2, 1)) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
