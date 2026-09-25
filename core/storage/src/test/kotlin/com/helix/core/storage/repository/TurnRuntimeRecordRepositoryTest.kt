package com.helix.core.storage.repository

import com.helix.core.storage.dao.TurnRuntimeRecordDao
import com.helix.core.storage.entity.TurnRuntimeRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnRuntimeRecordRepositoryTest {
    private val dao = FakeTurnRuntimeRecordDao()
    private val repository = TurnRuntimeRecordRepository(dao)

    @Test
    fun `create stores immutable start snapshot with zero checkpoints`() {
        val created = repository.create(record())

        assertEquals("turn-1", created.turnId)
        assertEquals("provider-1", created.providerId)
        assertEquals("model-1", created.modelId)
        assertEquals(0, created.consumedModelCalls)
        assertEquals(0L, created.consumedTokens)
        assertEquals(0, created.admittedToolRounds)
        assertEquals(created, repository.resolve("turn-1"))
    }

    @Test
    fun `new record rejects preconsumed budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.create(record(consumedModelCalls = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.create(record(consumedTokens = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.create(record(admittedToolRounds = 1))
        }
    }

    @Test
    fun `model admission is monotonic cas and stale owner conflicts`() {
        repository.create(record())

        val once = repository.checkpointModelAdmission("turn-1", expectedConsumedModelCalls = 0)
        assertEquals(1, once.consumedModelCalls)

        val error =
            assertThrows(IllegalStateException::class.java) {
                repository.checkpointModelAdmission("turn-1", expectedConsumedModelCalls = 0)
            }
        assertEquals(true, error.message?.startsWith("TURN_RUNTIME_CHECKPOINT_CONFLICT"))
        assertEquals(1, repository.resolve("turn-1").consumedModelCalls)
    }

    @Test
    fun `token checkpoint cannot refund and stale checkpoint conflicts`() {
        repository.create(record())

        val updated = repository.checkpointTokens("turn-1", expectedConsumedTokens = 0, consumedTokens = 321)
        assertEquals(321L, updated.consumedTokens)

        assertThrows(IllegalArgumentException::class.java) {
            repository.checkpointTokens("turn-1", expectedConsumedTokens = 321, consumedTokens = 320)
        }
        val stale =
            assertThrows(IllegalStateException::class.java) {
                repository.checkpointTokens("turn-1", expectedConsumedTokens = 0, consumedTokens = 400)
            }
        assertEquals(true, stale.message?.startsWith("TURN_RUNTIME_CHECKPOINT_CONFLICT"))
        assertEquals(321L, repository.resolve("turn-1").consumedTokens)
    }

    @Test
    fun `tool round checkpoint is monotonic cas`() {
        repository.create(record())

        val once = repository.checkpointToolRound("turn-1", expectedAdmittedToolRounds = 0)
        val twice = repository.checkpointToolRound("turn-1", expectedAdmittedToolRounds = 1)

        assertEquals(1, once.admittedToolRounds)
        assertEquals(2, twice.admittedToolRounds)
        assertThrows(IllegalStateException::class.java) {
            repository.checkpointToolRound("turn-1", expectedAdmittedToolRounds = 0)
        }
    }

    @Test
    fun `runtime checkpoints never rewrite immutable start snapshot`() {
        val initial = repository.create(record())
        repository.checkpointModelAdmission("turn-1", 0)
        repository.checkpointTokens("turn-1", 0, 99)
        repository.checkpointToolRound("turn-1", 0)

        val current = repository.resolve("turn-1")
        assertEquals(initial.providerId, current.providerId)
        assertEquals(initial.modelId, current.modelId)
        assertEquals(initial.providerSnapshot, current.providerSnapshot)
        assertEquals(initial.mode, current.mode)
        assertEquals(initial.chatToolsEnabled, current.chatToolsEnabled)
        assertEquals(initial.budgetsJson, current.budgetsJson)
        assertEquals(initial.reasoning, current.reasoning)
        assertEquals(initial.goalBudgetsJson, current.goalBudgetsJson)
    }

    private fun record(
        consumedModelCalls: Int = 0,
        consumedTokens: Long = 0,
        admittedToolRounds: Int = 0,
    ) = TurnRuntimeRecordEntity(
        turnId = "turn-1",
        version = TurnRuntimeRecordRepository.CURRENT_VERSION,
        providerId = "provider-1",
        modelId = "model-1",
        providerSnapshot = """{"model":"model-1"}""",
        mode = "ACT",
        chatToolsEnabled = true,
        budgetsJson = """{"maxModelCalls":8}""",
        reasoning = "LOW",
        goalBudgetsJson = """{"maxModelCalls":24}""",
        consumedModelCalls = consumedModelCalls,
        consumedTokens = consumedTokens,
        admittedToolRounds = admittedToolRounds,
    )

    private class FakeTurnRuntimeRecordDao : TurnRuntimeRecordDao {
        private val rows = mutableMapOf<String, TurnRuntimeRecordEntity>()

        override fun insert(record: TurnRuntimeRecordEntity) {
            check(record.turnId !in rows) { "duplicate" }
            rows[record.turnId] = record
        }

        override fun byTurn(turnId: String): TurnRuntimeRecordEntity? = rows[turnId]

        override fun incrementModelCalls(
            turnId: String,
            expected: Int,
        ): Int =
            rows[turnId]
                ?.takeIf { it.consumedModelCalls == expected }
                ?.let { row ->
                    rows[turnId] = row.copy(consumedModelCalls = row.consumedModelCalls + 1)
                    1
                } ?: 0

        override fun compareAndSetTokens(
            turnId: String,
            expected: Long,
            total: Long,
        ): Int =
            rows[turnId]
                ?.takeIf { it.consumedTokens == expected }
                ?.let { row ->
                    rows[turnId] = row.copy(consumedTokens = total)
                    1
                } ?: 0

        override fun incrementToolRounds(
            turnId: String,
            expected: Int,
        ): Int =
            rows[turnId]
                ?.takeIf { it.admittedToolRounds == expected }
                ?.let { row ->
                    rows[turnId] = row.copy(admittedToolRounds = row.admittedToolRounds + 1)
                    1
                } ?: 0
    }
}
