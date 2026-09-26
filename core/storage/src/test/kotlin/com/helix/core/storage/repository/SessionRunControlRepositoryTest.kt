package com.helix.core.storage.repository

import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.dao.SessionRunControlDao
import com.helix.core.storage.entity.SessionRunControlEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRunControlRepositoryTest {
    private val dao = FakeSessionRunControlDao()
    private val repository = SessionRunControlRepository(dao)

    @Test
    fun missingSessionHasNoSnapshot() {
        assertNull(repository.forSession("session"))
    }

    @Test
    fun roundTripAndRevisionAreStable() {
        val first = record(AgentMode.PLAN, ReasoningEffort.LOW)
        assertEquals(1L, repository.setForSession("session", first, 100L))
        assertEquals(first, repository.forSession("session"))

        val second = record(AgentMode.ACT, ReasoningEffort.HIGH)
        assertEquals(2L, repository.setForSession("session", second, 200L))
        assertEquals(second, repository.forSession("session"))
        assertEquals(100L, dao.rows.getValue("session").createdAtEpoch)
        assertEquals(200L, dao.rows.getValue("session").updatedAtEpoch)
    }

    @Test
    fun copyProducesAnIndependentTargetSnapshot() {
        val source = record(AgentMode.GOAL, ReasoningEffort.MEDIUM)
        repository.setForSession("source", source, 10L)

        assertEquals(1L, repository.copy("source", "target", 20L))
        assertEquals(source, repository.forSession("target"))

        repository.setForSession("source", record(AgentMode.CHAT, ReasoningEffort.OFF), 30L)
        assertEquals(source, repository.forSession("target"))
    }

    private fun record(
        mode: AgentMode,
        reasoning: ReasoningEffort,
    ) = SessionRunControlRecord(
        mode = mode,
        chatToolsEnabled = true,
        budgets = TurnBudgets(8, 9, 64_000, 4_096, 80_000),
        reasoning = reasoning,
        goalBudgets = GoalBudgets(16, 32, 200_000, 60_000, 20_000, 1),
    )
}

private class FakeSessionRunControlDao : SessionRunControlDao {
    val rows = mutableMapOf<String, SessionRunControlEntity>()

    override fun insert(entity: SessionRunControlEntity) {
        rows[entity.sessionId] = entity
    }

    override fun bySession(sessionId: String): SessionRunControlEntity? = rows[sessionId]
}
