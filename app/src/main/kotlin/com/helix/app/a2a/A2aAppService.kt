package com.helix.app.a2a

import com.helix.core.policy.DataSensitivity
import com.helix.extensions.a2a.A2aAgentCardSnapshot
import com.helix.extensions.a2a.A2aAgentConfig
import com.helix.extensions.a2a.A2aClients
import com.helix.extensions.a2a.A2aDiscoveryService
import com.helix.extensions.a2a.A2aDynamicToolBridge
import com.helix.extensions.a2a.A2aSessionCheckpointTracker
import com.helix.extensions.a2a.A2aToolCaller
import com.helix.extensions.a2a.A2aToolDispatchFacts
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject

class A2aAppService(
    private val storage: A2aStorageBridge,
    private val registry: ToolRegistry,
    private val implementations: ToolImplementationRegistry,
    private val runner: A2aTaskRunner,
    private val discovery: A2aDiscoveryService = A2aClients.discovery(storage.credentials()),
) {
    private val activeBridges = mutableMapOf<String, A2aDynamicToolBridge>()
    private val trackers = mutableMapOf<String, A2aSessionCheckpointTracker>()

    init {
        storage.enabledAgentIds().forEach(::registerEnabledAgent)
    }

    fun registerDisabled(
        id: String,
        cardEndpoint: String,
        authAlias: String?,
    ): A2aAgentConfig = storage.registerDisabled(id, cardEndpoint, authAlias)

    suspend fun testConnection(id: String): A2aAgentCardSnapshot =
        discovery.discover(storage.load(id)).also { snapshot ->
            storage.persistSnapshot(snapshot)
            if (storage.load(id).enabled) {
                registerEnabledAgent(id)
            } else {
                unregisterAgent(id)
            }
        }

    /** User action after reviewing the bounded Card and selecting exact remote Skills. */
    fun enable(
        snapshot: A2aAgentCardSnapshot,
        skillIds: Set<String>,
    ) {
        require(skillIds.all { selected -> snapshot.skills.any { it.id == selected } }) {
            "A2A Skill selection contains an unknown Skill"
        }
        storage.persistSnapshot(snapshot)
        storage.enable(snapshot.agentId.value, skillIds)
        registerEnabledAgent(snapshot.agentId.value)
    }

    fun disable(agentId: String) {
        storage.disable(agentId)
        unregisterAgent(agentId)
    }

    private fun unregisterAgent(agentId: String) {
        registry.replaceA2aAgent(agentId, emptyList())
        implementations.replaceA2aAgent(agentId, emptyList())
        activeBridges.remove(agentId)
    }

    fun reconcileTask(
        toolCallId: String,
        sessionId: String,
    ): JsonObject = runner.reconcile(toolCallId, sessionId)

    fun cancelTask(
        toolCallId: String,
        sessionId: String,
    ): JsonObject = runner.cancel(toolCallId, sessionId)

    fun dispatchFacts(
        sessionId: String,
        descriptor: ToolDescriptor,
        arguments: JsonObject,
        sensitivity: DataSensitivity,
    ): A2aToolDispatchFacts? =
        (descriptor.origin as? ToolOrigin.A2aOrigin)?.let { origin ->
            activeBridges[origin.agentId]?.let { bridge ->
                val tracker = trackers.getOrPut("$sessionId:${origin.agentId}") { A2aSessionCheckpointTracker() }
                bridge.dispatchFacts(descriptor, arguments, sensitivity, tracker)
            }
        }

    private fun registerEnabledAgent(agentId: String) {
        val skills = storage.enabledSkills(agentId)
        if (skills.isEmpty()) {
            registry.replaceA2aAgent(agentId, emptyList())
            implementations.replaceA2aAgent(agentId, emptyList())
            activeBridges.remove(agentId)
            return
        }
        val bridge = A2aDynamicToolBridge(skills.first().agentId, skills, A2aToolCaller(runner::execute))
        try {
            bridge.register(registry, implementations)
            activeBridges[agentId] = bridge
        } catch (failure: IllegalArgumentException) {
            storage.disable(agentId)
            registry.replaceA2aAgent(agentId, emptyList())
            implementations.replaceA2aAgent(agentId, emptyList())
            throw failure
        }
    }
}
