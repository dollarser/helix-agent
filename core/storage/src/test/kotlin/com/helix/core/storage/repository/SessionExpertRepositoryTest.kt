package com.helix.core.storage.repository

import com.helix.core.model.AgentMode
import com.helix.core.storage.dao.SessionExpertDao
import com.helix.core.storage.entity.SessionExpertEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpertRepositoryTest {
    @Test
    fun `one durable expert row per session survives repository recreation`() {
        val dao = FakeSessionExpertDao()
        val first = SessionExpertRepository(dao)
        val profile =
            ExpertProfile(
                id = "expert-1",
                displayName = "Code reviewer",
                instruction = "Prioritize correctness and concise explanations.",
                recommendedSkillIds = listOf("skill-a"),
                recommendedConnectorIds = listOf("connector-a"),
                recommendedMode = AgentMode.PLAN,
            )

        assertEquals(1L, first.setForSession("session-a", profile, 10))
        val recreated = SessionExpertRepository(dao)
        assertEquals(profile, recreated.forSession("session-a"))

        val replacement = profile.copy(displayName = "Implementation reviewer")
        assertEquals(2L, recreated.setForSession("session-a", replacement, 20))
        assertEquals(replacement, first.forSession("session-a"))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `clearing one session expert leaves other sessions unchanged`() {
        val dao = FakeSessionExpertDao()
        val repository = SessionExpertRepository(dao)
        repository.setForSession("a", ExpertProfile("a", "A", "Instruction A"), 1)
        repository.setForSession("b", ExpertProfile("b", "B", "Instruction B"), 1)

        assertTrue(repository.clearForSession("a"))
        assertNull(repository.forSession("a"))
        assertEquals("B", repository.forSession("b")?.displayName)
    }

    private class FakeSessionExpertDao : SessionExpertDao {
        val rows = linkedMapOf<String, SessionExpertEntity>()

        override fun insert(entity: SessionExpertEntity) {
            rows[entity.sessionId] = entity
        }

        override fun bySession(sessionId: String): SessionExpertEntity? = rows[sessionId]

        override fun deleteBySession(sessionId: String): Int = if (rows.remove(sessionId) != null) 1 else 0
    }
}
