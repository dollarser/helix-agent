package com.helix.core.storage.repository

import com.helix.core.storage.dao.A2aAgentDao
import com.helix.core.storage.dao.A2aCapabilityDao
import com.helix.core.storage.entity.A2aAgentEntity
import com.helix.core.storage.entity.A2aCapabilityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aRepositoriesTest {
    @Test
    fun `Agent config is canonical alias-only and disabled without a Card snapshot`() {
        val dao = FakeA2aAgentDao()
        val repository = A2aAgentRepository(dao)

        val saved =
            repository.registerDisabled(
                A2aAgentSpec("agent-1", "HTTPS://Agent.Example/.well-known/agent-card.json", "a2a.agent.1"),
            )

        assertEquals("https://agent.example:443/.well-known/agent-card.json", saved.endpointRef)
        assertEquals("a2a.agent.1", saved.authAlias)
        assertFalse(saved.enabled)
        assertEquals(null, saved.cardHash)
        assertThrows(IllegalArgumentException::class.java) { repository.setEnabled("agent-1", true) }
    }

    @Test
    fun `unchanged Card preserves exact Skill enablement and any Card change disables all Skills`() {
        val agents = FakeA2aAgentDao()
        val capabilities = FakeA2aCapabilityDao(agents)
        val agentRepository = A2aAgentRepository(agents)
        val repository = A2aCapabilityRepository(agents, capabilities)
        agentRepository.registerDisabled(A2aAgentSpec("agent-1", "https://agent.example/card", null))

        val first =
            repository.replaceSnapshot(
                "agent-1",
                "a".repeat(64),
                listOf(skill("echo", '1'), skill("search", '2')),
            )
        assertTrue(first.none { it.enabled })
        agentRepository.setEnabled("agent-1", true)
        repository.setEnabled("agent-1", "echo", true)

        val unchanged =
            repository.replaceSnapshot(
                "agent-1",
                "a".repeat(64),
                listOf(skill("echo", '1'), skill("search", '2')),
            )
        assertTrue(unchanged.single { it.skillId == "echo" }.enabled)
        assertFalse(unchanged.single { it.skillId == "search" }.enabled)

        val changedCard =
            repository.replaceSnapshot(
                "agent-1",
                "b".repeat(64),
                listOf(skill("echo", '1'), skill("search", '2')),
            )
        assertTrue(changedCard.none { it.enabled })
        assertEquals("b".repeat(64), agents.byId("agent-1")?.cardHash)
        assertFalse(agents.byId("agent-1")!!.enabled)
    }

    @Test
    fun `changed interface version modes or Skill hash cannot retain enablement`() {
        val agents = FakeA2aAgentDao()
        val capabilities = FakeA2aCapabilityDao(agents)
        val agentRepository = A2aAgentRepository(agents)
        val repository = A2aCapabilityRepository(agents, capabilities)
        agentRepository.registerDisabled(A2aAgentSpec("agent-1", "https://agent.example/card", null))
        repository.replaceSnapshot("agent-1", "a".repeat(64), listOf(skill("echo", '1')))
        agentRepository.setEnabled("agent-1", true)

        listOf(
            skill("echo", '2'),
            skill("echo", '1').copy(interfaceUrl = "https://agent.example/other"),
            skill("echo", '1').copy(binding = "HTTP+JSON"),
            skill("echo", '1').copy(inputModes = listOf("application/json")),
        ).forEach { changed ->
            repository.setEnabled("agent-1", "echo", true)
            assertFalse(repository.replaceSnapshot("agent-1", "a".repeat(64), listOf(changed)).single().enabled)
            repository.replaceSnapshot("agent-1", "a".repeat(64), listOf(skill("echo", '1')))
        }
    }

    @Test
    fun `unknown selection and malformed duplicate snapshot fail without deleting the previous snapshot`() {
        val agents = FakeA2aAgentDao()
        val capabilities = FakeA2aCapabilityDao(agents)
        val agentRepository = A2aAgentRepository(agents)
        val repository = A2aCapabilityRepository(agents, capabilities)
        agentRepository.registerDisabled(A2aAgentSpec("agent-1", "https://agent.example/card", null))
        repository.replaceSnapshot("agent-1", "a".repeat(64), listOf(skill("echo", '1')))

        assertThrows(IllegalArgumentException::class.java) { repository.setEnabled("agent-1", "missing", true) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.replaceSnapshot(
                "agent-1",
                "b".repeat(64),
                listOf(skill("duplicate", '2'), skill("duplicate", '3')),
            )
        }
        assertEquals(listOf("echo"), repository.listByAgent("agent-1").map { it.skillId })
        assertEquals("a".repeat(64), agents.byId("agent-1")?.cardHash)
    }

    private fun skill(
        id: String,
        hash: Char,
    ): A2aSkillSpec =
        A2aSkillSpec(
            interfaceUrl = "https://agent.example/a2a",
            binding = "JSONRPC",
            protocolVersion = "1.0",
            skillId = id,
            skillHash = hash.toString().repeat(64),
            inputModes = listOf("text/plain"),
            outputModes = listOf("text/plain", "application/json"),
        )
}

private class FakeA2aAgentDao : A2aAgentDao {
    val rows = linkedMapOf<String, A2aAgentEntity>()

    override fun insert(agent: A2aAgentEntity) {
        check(rows.putIfAbsent(agent.id, agent) == null)
    }

    override fun byId(id: String): A2aAgentEntity? = rows[id]

    override fun list(): List<A2aAgentEntity> = rows.values.toList()

    override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Int {
        val current = rows[id] ?: return 0
        rows[id] = current.copy(enabled = enabled)
        return 1
    }
}

private class FakeA2aCapabilityDao(
    private val agents: FakeA2aAgentDao,
) : A2aCapabilityDao {
    private val rows = mutableListOf<A2aCapabilityEntity>()
    private var nextId = 1L

    override fun insertAll(capabilities: List<A2aCapabilityEntity>) {
        capabilities.forEach { rows += it.copy(rowId = nextId++) }
    }

    override fun listByAgent(agentId: String): List<A2aCapabilityEntity> = rows.filter { it.agentId == agentId }

    override fun deleteByAgent(agentId: String) {
        rows.removeAll { it.agentId == agentId }
    }

    override fun setEnabled(
        rowId: Long,
        enabled: Boolean,
    ): Int {
        val index = rows.indexOfFirst { it.rowId == rowId }
        if (index < 0) return 0
        rows[index] = rows[index].copy(enabled = enabled)
        return 1
    }

    override fun setSnapshotState(
        agentId: String,
        cardHash: String,
        disableAgent: Boolean,
    ): Int {
        val current = agents.rows[agentId] ?: return 0
        agents.rows[agentId] = current.copy(cardHash = cardHash, enabled = if (disableAgent) false else current.enabled)
        return 1
    }
}
