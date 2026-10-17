package com.helix.app.runcontrol

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RunControlStoreTest {
    private val defaults = RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT)

    @Test fun failedPersistenceCannotPublishUnsavedBudgets() {
        val lines =
            object : com.helix.app.internal.LineStore {
                override fun lines(key: String): List<String> = emptyList()

                override fun setLines(
                    key: String,
                    lines: List<String>,
                ) {
                    error("disk failure")
                }
            }
        val store = PersistedRunControlStore(lines)
        val original = store.current
        assertThrows(IllegalStateException::class.java) { store.setBudgets(TurnBudgets(2048)) }
        assertEquals(original, store.current)
        assertThrows(IllegalStateException::class.java) {
            store.setGoalBudgets(
                com.helix.core.model
                    .GoalBudgets(maxGoalRounds = 7),
            )
        }
        assertEquals(original, store.current)
    }

    @Test fun defaultsUseOnlyTheCurrentOutputAllowance() {
        assertEquals(defaults, PersistedRunControlStore(InMemoryLineStore()).current)
        assertEquals(16384L, TurnBudgetBounds.validate(TurnBudgetBounds.DEFAULT).maxOutputTokens)
    }

    @Test fun explicitOutputLimitsAreNeverRaisedOnReload() {
        for (output in listOf(1L, 4096L, TurnBudgetBounds.MAX_OUTPUT_TOKENS)) {
            val lines = InMemoryLineStore()
            val saved = TurnBudgets(output)
            PersistedRunControlStore(lines).setBudgets(saved)
            assertEquals(saved, PersistedRunControlStore(lines).current.budgets)
        }
    }

    @Test fun modeToolsReasoningAndOutputSurviveReconstruction() {
        val lines = InMemoryLineStore()
        val first = PersistedRunControlStore(lines)
        first.setMode(AgentMode.GOAL)
        first.setChatToolsEnabled(true)
        first.setBudgets(TurnBudgets(2000))
        first.setReasoning(ReasoningEffort.valueOf("XHIGH"))
        assertEquals(first.current, PersistedRunControlStore(lines).current)
        assertEquals(4, lines.lines("run_control_v1").size)
    }

    @Test fun malformedSettingsFailClosedWithoutPartiallyRestoringActMode() {
        val valid = listOf("ACT", "true", TurnBudgets(2000).toStorageString(), "HIGH")
        for (invalid in listOf(
            emptyList(),
            valid.take(3),
            valid + "budgets_v3",
            valid.toMutableList().apply { this[0] = "INVALID" },
            valid.toMutableList().apply { this[1] = "yes" },
            valid.toMutableList().apply { this[2] = "{}" },
            valid.toMutableList().apply { this[3] = "not a token" },
        )) {
            val lines = InMemoryLineStore()
            lines.setLines("run_control_v1", invalid)
            assertEquals(defaults, PersistedRunControlStore(lines).current)
        }
    }

    @Test fun obsoleteBudgetShapeIsRejectedAndReplacedWhenUserSaves() {
        val lines = InMemoryLineStore()
        val old =
            """{"maxSteps":8,"maxModelCalls":9,"maxInputTokens":128000,""" +
                """"maxOutputTokens":4096,"maxTotalTokens":160000}"""
        lines.setLines("run_control_v1", listOf("ACT", "true", old, "HIGH"))
        val store = PersistedRunControlStore(lines)
        assertEquals(defaults, store.current)
        store.setBudgets(TurnBudgets(4096))
        assertEquals(TurnBudgets(4096), PersistedRunControlStore(lines).current.budgets)
    }

    @Test fun invalidOutputLimitsDoNotOverwriteSavedSettings() {
        val lines = InMemoryLineStore()
        val store = PersistedRunControlStore(lines)
        store.setBudgets(TurnBudgets(2000))
        for (invalid in listOf(0L, TurnBudgetBounds.MAX_OUTPUT_TOKENS + 1)) {
            assertThrows(IllegalArgumentException::class.java) { store.setBudgets(TurnBudgets(invalid)) }
            assertEquals(TurnBudgets(2000), PersistedRunControlStore(lines).current.budgets)
        }
    }
}
