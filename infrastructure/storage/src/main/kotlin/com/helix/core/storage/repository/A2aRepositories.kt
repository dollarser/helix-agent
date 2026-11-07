package com.helix.core.storage.repository

import com.helix.core.model.A2aAgentId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import com.helix.core.model.Sha256
import com.helix.core.storage.dao.A2aAgentDao
import com.helix.core.storage.dao.A2aCapabilityDao
import com.helix.core.storage.entity.A2aAgentEntity
import com.helix.core.storage.entity.A2aCapabilityEntity
import com.helix.core.storage.mapping.EntityMappers

data class A2aAgentSpec(
    val id: String,
    val endpoint: String,
    val authAlias: String?,
) {
    internal fun toEntity(): A2aAgentEntity {
        A2aAgentId(id)
        val normalized = NormalizedEndpoint.parse(endpoint)
        require(normalized.scheme == "https" || normalized.isLiteralLoopback()) {
            "A2A Agent Card endpoint must use HTTPS"
        }
        authAlias?.let(::SecretAlias)
        return A2aAgentEntity(id, normalized.full, authAlias, enabled = false, cardHash = null)
    }
}

data class A2aSkillSpec(
    val interfaceUrl: String,
    val binding: String,
    val protocolVersion: String,
    val tenant: String? = null,
    val skillId: String,
    val skillHash: String,
    val inputModes: List<String>,
    val outputModes: List<String>,
) {
    init {
        NormalizedEndpoint.parse(interfaceUrl)
        require(binding == "JSONRPC" || binding == "HTTP+JSON") { "unsupported A2A binding" }
        require(protocolVersion == "1.0") { "unsupported A2A protocol version" }
        require(tenant == null || (tenant.isNotBlank() && tenant.length <= 256)) { "invalid A2A tenant" }
        require(skillId.isNotBlank() && skillId.length <= 256) { "invalid A2A Skill ID" }
        Sha256(skillHash)
        require(inputModes.size <= 32 && outputModes.size <= 32) { "A2A Skill mode list exceeds limit" }
        require((inputModes + outputModes).all { it.isNotBlank() && it.length <= 128 }) {
            "invalid A2A Skill mode"
        }
    }

    internal fun toEntity(
        agentId: String,
        enabled: Boolean,
    ): A2aCapabilityEntity =
        A2aCapabilityEntity(
            rowId = 0,
            agentId = agentId,
            interfaceUrl = NormalizedEndpoint.parse(interfaceUrl).full,
            binding = binding,
            protocolVersion = protocolVersion,
            tenant = tenant,
            skillId = skillId,
            skillHash = skillHash,
            inputModes = EntityMappers.stringListJson(inputModes),
            outputModes = EntityMappers.stringListJson(outputModes),
            enabled = enabled,
        )
}

class A2aAgentRepository(
    private val dao: A2aAgentDao,
) {
    fun registerDisabled(spec: A2aAgentSpec): A2aAgentEntity {
        val entity = spec.toEntity()
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): A2aAgentEntity = dao.byId(id) ?: throw IllegalArgumentException("A2A agent not found: $id")

    fun list(): List<A2aAgentEntity> = dao.list()

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val current = resolve(id)
        require(!enabled || current.cardHash != null) { "A2A agent has no tested Agent Card snapshot" }
        require(dao.setEnabled(id, enabled) == 1) { "A2A agent not found: $id" }
    }

    fun delete(id: String) {
        require(dao.delete(id) == 1) { "A2A agent not found: $id" }
    }
}

class A2aCapabilityRepository(
    private val agentDao: A2aAgentDao,
    private val capabilityDao: A2aCapabilityDao,
) {
    /**
     * Replaces the complete Card snapshot. Any Card hash change disables every Skill even when
     * a single Skill's local fields happen to be unchanged; unchanged snapshots preserve exact
     * per-Skill user enablement.
     */
    fun replaceSnapshot(
        agentId: String,
        cardHash: String,
        skills: List<A2aSkillSpec>,
    ): List<A2aCapabilityEntity> {
        A2aAgentId(agentId)
        Sha256(cardHash)
        require(skills.size <= 256) { "A2A Skill snapshot exceeds 256 entries" }
        require(skills.map { it.skillId }.toSet().size == skills.size) { "A2A Skill snapshot has duplicate IDs" }
        val agent = agentDao.byId(agentId) ?: throw IllegalArgumentException("A2A agent not found: $agentId")
        val previous = capabilityDao.listByAgent(agentId).associateBy { it.skillId }
        val cardUnchanged = agent.cardHash == cardHash
        val replacements =
            skills.map { spec ->
                val old = previous[spec.skillId]
                val exactUnchanged =
                    cardUnchanged &&
                        old != null &&
                        old.interfaceUrl == NormalizedEndpoint.parse(spec.interfaceUrl).full &&
                        old.binding == spec.binding &&
                        old.protocolVersion == spec.protocolVersion &&
                        old.tenant == spec.tenant &&
                        old.skillHash == spec.skillHash &&
                        old.inputModes == EntityMappers.stringListJson(spec.inputModes) &&
                        old.outputModes == EntityMappers.stringListJson(spec.outputModes)
                spec.toEntity(agentId, enabled = exactUnchanged && old.enabled)
            }
        capabilityDao.replaceSnapshot(agentId, cardHash, disableAgent = !cardUnchanged, replacements)
        return capabilityDao.listByAgent(agentId)
    }

    fun listByAgent(agentId: String): List<A2aCapabilityEntity> = capabilityDao.listByAgent(agentId)

    fun setEnabled(
        agentId: String,
        skillId: String,
        enabled: Boolean,
    ) {
        val agent = agentDao.byId(agentId) ?: throw IllegalArgumentException("A2A agent not found: $agentId")
        require(!enabled || agent.enabled) { "A2A agent must be enabled before enabling a Skill" }
        val row =
            capabilityDao.listByAgent(agentId).singleOrNull { it.skillId == skillId }
                ?: throw IllegalArgumentException("A2A Skill not found: $skillId")
        require(capabilityDao.setEnabled(row.rowId, enabled) == 1) { "A2A Skill not found: $skillId" }
    }
}

private fun NormalizedEndpoint.isLiteralLoopback(): Boolean = scheme == "http" && (host == "127.0.0.1" || host == "::1")
