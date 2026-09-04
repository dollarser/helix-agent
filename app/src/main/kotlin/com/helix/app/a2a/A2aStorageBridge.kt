package com.helix.app.a2a

import com.helix.core.model.A2aAgentId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.EntityMappers
import com.helix.core.storage.repository.A2aAgentSpec
import com.helix.core.storage.repository.A2aSkillSpec
import com.helix.extensions.a2a.A2aAgentCardSnapshot
import com.helix.extensions.a2a.A2aAgentConfig
import com.helix.extensions.a2a.A2aBinding
import com.helix.extensions.a2a.A2aCredentialLookup
import com.helix.extensions.a2a.A2aEnabledSkill
import com.helix.extensions.a2a.A2aInterfaceSnapshot

class A2aStorageBridge(
    private val storage: HelixStorage,
) {
    fun registerDisabled(
        id: String,
        cardEndpoint: String,
        authAlias: String?,
    ): A2aAgentConfig {
        storage.a2aAgents.registerDisabled(A2aAgentSpec(id, cardEndpoint, authAlias))
        return load(id)
    }

    fun load(id: String): A2aAgentConfig {
        val entity = storage.a2aAgents.resolve(id)
        return A2aAgentConfig(
            id = A2aAgentId(entity.id),
            cardEndpoint = NormalizedEndpoint.parse(entity.endpointRef),
            bearerSecretAlias = entity.authAlias?.let(::SecretAlias),
            enabled = entity.enabled,
        )
    }

    fun credentials(): A2aCredentialLookup = A2aCredentialLookup { alias -> storage.secrets.get(alias) }

    fun enabledAgentIds(): List<String> =
        storage.a2aAgents
            .list()
            .filter { it.enabled }
            .map { it.id }

    fun enabledSkills(agentId: String): List<A2aEnabledSkill> {
        val agent = storage.a2aAgents.resolve(agentId)
        require(agent.enabled) { "A2A Agent is disabled" }
        val cardHash = requireNotNull(agent.cardHash) { "A2A Agent has no Card snapshot" }
        return storage.a2aCapabilities.listByAgent(agentId).filter { it.enabled }.map { skill ->
            A2aEnabledSkill(
                agentId = A2aAgentId(agentId),
                skillId = skill.skillId,
                interfaceSnapshot =
                    A2aInterfaceSnapshot(
                        endpoint = NormalizedEndpoint.parse(skill.interfaceUrl),
                        binding = A2aBinding.entries.single { it.wireName == skill.binding },
                        protocolVersion = skill.protocolVersion,
                        tenant = skill.tenant,
                    ),
                cardHash = cardHash,
                skillHash = skill.skillHash,
                inputModes = EntityMappers.parseStringListJson(skill.inputModes),
                outputModes = EntityMappers.parseStringListJson(skill.outputModes),
            )
        }
    }

    fun persistSnapshot(snapshot: A2aAgentCardSnapshot) {
        val config = load(snapshot.agentId.value)
        require(config.cardEndpoint.origin == snapshot.selectedInterface.endpoint.origin) {
            "A2A Agent Card origin changed during discovery"
        }
        val contentRef = storage.contentStore.write(snapshot.canonicalCardJson)
        require(contentRef.sha256 == snapshot.cardHash.hex) { "A2A Agent Card content hash mismatch" }
        val selected = snapshot.selectedInterface
        storage.a2aCapabilities.replaceSnapshot(
            agentId = snapshot.agentId.value,
            cardHash = snapshot.cardHash.hex,
            skills =
                snapshot.skills.map { skill ->
                    A2aSkillSpec(
                        interfaceUrl = selected.endpoint.full,
                        binding = selected.binding.wireName,
                        protocolVersion = selected.protocolVersion,
                        tenant = selected.tenant,
                        skillId = skill.id,
                        skillHash = skill.contentHash.hex,
                        inputModes = skill.inputModes,
                        outputModes = skill.outputModes,
                    )
                },
        )
    }

    fun enable(
        agentId: String,
        skillIds: Set<String>,
    ) {
        require(skillIds.isNotEmpty()) { "at least one A2A Skill must be selected" }
        val available = storage.a2aCapabilities.listByAgent(agentId).mapTo(mutableSetOf()) { it.skillId }
        require(skillIds.all { it in available }) { "A2A Skill selection contains an unknown Skill" }
        storage.a2aAgents.setEnabled(agentId, true)
        available.forEach { skillId -> storage.a2aCapabilities.setEnabled(agentId, skillId, skillId in skillIds) }
    }

    fun disable(agentId: String) {
        storage.a2aCapabilities.listByAgent(agentId).forEach { skill ->
            storage.a2aCapabilities.setEnabled(agentId, skill.skillId, false)
        }
        storage.a2aAgents.setEnabled(agentId, false)
    }
}
