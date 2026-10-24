package com.helix.app.infrastructure.agent

import com.helix.app.infrastructure.agent.TurnRuntimeRecordCodec
import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.entity.TurnRuntimeRecordEntity
import com.helix.core.storage.repository.TurnRuntimeRecordRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnRuntimeRecordCodecTest {
    @Test
    fun `start record round trips exact admitted control and provider identity`() {
        val control =
            RunControlConfig(
                mode = AgentMode.ACT,
                chatToolsEnabled = true,
                budgets = TurnBudgets(maxOutputTokens = 4_000),
                reasoning = ReasoningEffort.MEDIUM,
                goalBudgets = GoalBudgetDefaults.VALUE,
            )

        val row =
            TurnRuntimeRecordCodec.startRecord(
                turnId = "turn-1",
                providerId = "provider-1",
                modelId = "model-1",
                providerSnapshot = """{"model":"model-1","endpoint":"https://example.invalid"}""",
                control = control,
            )
        val restored = TurnRuntimeRecordCodec.decode(row)

        assertEquals("turn-1", restored.turnId)
        assertEquals("provider-1", restored.providerId)
        assertEquals("model-1", restored.modelId)
        assertEquals(control, restored.control)
        assertEquals(0, restored.consumedModelCalls)
        assertEquals(0L, restored.consumedTokens)
        assertEquals(0, restored.admittedToolRounds)
    }

    @Test
    fun `decode never falls back from corrupt execution policy`() {
        val base = validRecord()

        assertThrows(IllegalArgumentException::class.java) {
            TurnRuntimeRecordCodec.decode(base.copy(mode = "NOT_A_MODE"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnRuntimeRecordCodec.decode(base.copy(reasoning = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnRuntimeRecordCodec.decode(base.copy(budgetsJson = "{}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnRuntimeRecordCodec.decode(base.copy(goalBudgetsJson = "{}"))
        }
    }

    @Test
    fun `decode retains usage beyond historical limits and rejects negative usage`() {
        val base = validRecord()
        val decoded =
            TurnRuntimeRecordCodec.decode(
                base.copy(consumedModelCalls = 10, consumedTokens = 60_001, admittedToolRounds = 8),
            )
        assertEquals(10, decoded.consumedModelCalls)
        assertEquals(60_001L, decoded.consumedTokens)
        assertEquals(8, decoded.admittedToolRounds)
        assertThrows(
            IllegalArgumentException::class.java,
        ) { TurnRuntimeRecordCodec.decode(base.copy(consumedTokens = -1)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { TurnRuntimeRecordCodec.decode(base.copy(consumedModelCalls = -1)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { TurnRuntimeRecordCodec.decode(base.copy(admittedToolRounds = -1)) }
    }

    private fun validRecord() =
        TurnRuntimeRecordEntity(
            turnId = "turn-1",
            version = TurnRuntimeRecordRepository.CURRENT_VERSION,
            providerId = "provider-1",
            modelId = "model-1",
            providerSnapshot = """{"model":"model-1"}""",
            mode = AgentMode.ACT.name,
            chatToolsEnabled = true,
            budgetsJson = TurnBudgets(maxOutputTokens = 4_000).toStorageString(),
            reasoning = ReasoningEffort.LOW.name,
            goalBudgetsJson = GoalBudgetDefaults.VALUE.toStorageString(),
            consumedModelCalls = 1,
            consumedTokens = 1,
            admittedToolRounds = 1,
        )
}
