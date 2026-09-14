package com.helix.app.runcontrol

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunControlStoreTest {
    @Test fun previousTemplateMigratesButExplicitV3AndCustomValuesRemain() {
        val lines = InMemoryLineStore()
        val old = TurnBudgetBounds.PREVIOUS_DEFAULT
        lines.setLines("run_control_v1", listOf("ACT", "true", old.toStorageString(), "HIGH", "budgets_v2"))
        val migrated = PersistedRunControlStore(lines)
        assertEquals(TurnBudgetBounds.DEFAULT, migrated.current.budgets)
        assertEquals(com.helix.core.model.ReasoningEffort.HIGH, migrated.current.reasoning)
        migrated.setBudgets(old)
        assertEquals(old, PersistedRunControlStore(lines).current.budgets)
        val custom = old.copy(maxTotalTokens = 250000)
        lines.setLines("run_control_v1", listOf("ACT", "true", custom.toStorageString(), "HIGH", "budgets_v2"))
        assertEquals(custom, PersistedRunControlStore(lines).current.budgets)
    }

    @Test fun defaultsProvideCompactionHeadroomAndLongerOutputWithinExistingCaps() {
        val defaults = TurnBudgetBounds.validate(TurnBudgetBounds.DEFAULT)
        assertEquals(32, defaults.maxSteps)
        assertEquals(48, defaults.maxModelCalls)
        assertEquals(16384L, defaults.maxOutputTokens)
        assertEquals(1000000L, defaults.maxTotalTokens)
    }

    @Test
    fun legacyDefaultUpgradesWhileCustomBudgetsStayUnchanged() {
        val lines = InMemoryLineStore()
        lines.setLines("run_control_v1", listOf("ACT", "false", TurnBudgetBounds.LEGACY_DEFAULT.toStorageString()))
        assertEquals(32, PersistedRunControlStore(lines).current.budgets.maxSteps)
        assertEquals(48, PersistedRunControlStore(lines).current.budgets.maxModelCalls)
        val custom = TurnBudgetBounds.LEGACY_DEFAULT.copy(maxSteps = 3)
        PersistedRunControlStore(lines).setBudgets(custom)
        assertEquals(custom, PersistedRunControlStore(lines).current.budgets)
        PersistedRunControlStore(lines).setBudgets(TurnBudgetBounds.LEGACY_DEFAULT)
        assertEquals(TurnBudgetBounds.LEGACY_DEFAULT, PersistedRunControlStore(lines).current.budgets)
    }

    @Test fun reasoningSurvivesReconstructionAndLegacyModesArePreserved() {
        val lines = InMemoryLineStore()
        lines.setLines("run_control_v1", listOf("ACT", "false", TurnBudgetBounds.DEFAULT.toStorageString()))
        val store = PersistedRunControlStore(lines)
        assertEquals(AgentMode.ACT, store.current.mode)
        assertEquals(com.helix.core.model.ReasoningEffort.OFF, store.current.reasoning)
        store.setReasoning(com.helix.core.model.ReasoningEffort.MEDIUM)
        val restored = PersistedRunControlStore(lines).current
        assertEquals(AgentMode.ACT, restored.mode)
        assertEquals(com.helix.core.model.ReasoningEffort.MEDIUM, restored.reasoning)
    }

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
