package com.helix.app.mcp

import com.helix.core.model.SafetyProfile
import com.helix.core.policy.DataSensitivity
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpHandshakeService
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpSessionCheckpointTracker
import com.helix.extensions.mcp.McpSessionSendSummary
import com.helix.extensions.mcp.McpSsrfEndpointGate
import com.helix.extensions.mcp.McpToolDispatchFacts
import com.helix.extensions.mcp.McpToolRuntime
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

class McpAppService(
    private val storage: McpStorageBridge,
    private val profile: () -> SafetyProfile,
    private val registry: ToolRegistry,
    private val implementations: ToolImplementationRegistry,
) {
    private val endpointGate = McpSsrfEndpointGate(profile, { emptySet() })
    private val handshake = McpHandshakeService(storage.credentials(), endpointGate, "Helix", "1")
    private val runtime = McpToolRuntime(storage.credentials(), endpointGate, "Helix", "1")
    private val activeBridges = ConcurrentHashMap<String, McpDynamicToolBridge>()
    private val trackers = ConcurrentHashMap<String, McpSessionCheckpointTracker>()
    private val pendingSummaries = ConcurrentHashMap<String, PendingSend>()
    private val sentBySession = ConcurrentHashMap<String, ArrayDeque<McpSessionSendSummary>>()

    fun registerDisabled(
        id: String,
        endpoint: String,
        authAlias: String?,
    ): McpServerConfig = storage.registerDisabled(id, endpoint, authAlias)

    suspend fun testConnection(id: String): McpHandshakeSnapshot = handshake.testConnection(storage.load(id))

    /** User action after reviewing the test snapshot and exact tool selection. */
    @Suppress("TooGenericExceptionCaught") // any failed registration must roll persisted enablement back closed
    fun enable(
        snapshot: McpHandshakeSnapshot,
        toolNames: Set<String>,
    ) {
        val available = snapshot.metadata.tools.associateBy { it.name }
        require(toolNames.isNotEmpty()) { "at least one MCP tool must be selected" }
        require(toolNames.all { it in available }) { "MCP tool selection contains an unknown tool" }
        val enabledConfig = storage.load(snapshot.serverId.value).copy(enabled = true)
        val selected = toolNames.sorted().map { McpToolSchemaAdapter.adapt(available.getValue(it)) }
        lateinit var bridge: McpDynamicToolBridge
        val baseCaller =
            runtime.caller(enabledConfig) { call ->
                check(activeBridges[snapshot.serverId.value] === bridge) { "MCP_SERVER_DISABLED_OR_REPLACED" }
                pendingSummaries.remove(call.toolCallId)?.let { pending ->
                    recordSent(pending.sessionId, pending.summary)
                }
            }
        bridge =
            McpDynamicToolBridge(
                config = enabledConfig,
                identity = snapshot.identity,
                metadata = selected,
                caller =
                    com.helix.extensions.mcp.McpToolCaller { call, name ->
                        check(activeBridges[snapshot.serverId.value] === bridge) { "MCP_SERVER_DISABLED_OR_REPLACED" }
                        baseCaller.call(call, name)
                    },
            )
        storage.persistHandshake(snapshot)
        storage.setEnabledTools(snapshot.serverId.value, toolNames)
        storage.setServerEnabled(snapshot.serverId.value, true)
        try {
            bridge.register(registry, implementations)
            activeBridges[snapshot.serverId.value] = bridge
        } catch (failure: Throwable) {
            storage.setServerEnabled(snapshot.serverId.value, false)
            storage.setEnabledTools(snapshot.serverId.value, emptySet())
            throw failure
        }
    }

    fun isActive(serverId: String): Boolean = activeBridges.containsKey(serverId)

    fun disable(serverId: String) {
        activeBridges.remove(serverId)
        storage.setServerEnabled(serverId, false)
        storage.setEnabledTools(serverId, emptySet())
        registry.replaceMcpServer(serverId, emptyList())
        implementations.replaceMcpServer(serverId, emptyList())
    }

    fun delete(serverId: String) {
        disable(serverId)
        trackers.keys.removeIf { it.endsWith(":$serverId") }
        pendingSummaries.clear()
        sentBySession.clear()
        storage.delete(serverId)
    }

    @Suppress("ReturnCount") // non-MCP and inactive-server exits are distinct fail-closed boundaries
    fun dispatchFacts(
        sessionId: String,
        toolCallId: String,
        descriptor: ToolDescriptor,
        arguments: JsonObject,
        sensitivity: DataSensitivity,
    ): McpToolDispatchFacts? {
        val origin = descriptor.origin as? com.helix.tools.framework.ToolOrigin.McpOrigin ?: return null
        val bridge = activeBridges[origin.serverId] ?: return null
        val tracker = trackers.getOrPut("$sessionId:${origin.serverId}") { McpSessionCheckpointTracker() }
        val facts = bridge.dispatchFacts(descriptor, arguments, sensitivity, tracker)
        pendingSummaries[toolCallId] = PendingSend(sessionId, facts.sendSummary)
        if (pendingSummaries.size > MAX_PENDING_SUMMARIES) pendingSummaries.clear()
        return facts
    }

    fun sentSummaries(sessionId: String): List<McpSessionSendSummary> =
        sentBySession[sessionId]?.let { summaries -> synchronized(summaries) { summaries.toList() } }.orEmpty()

    private fun recordSent(
        sessionId: String,
        summary: McpSessionSendSummary,
    ) {
        val summaries = sentBySession.getOrPut(sessionId) { ArrayDeque() }
        synchronized(summaries) {
            summaries.addLast(summary)
            while (summaries.size > MAX_SESSION_SUMMARIES) summaries.removeFirst()
        }
    }

    private companion object {
        const val MAX_PENDING_SUMMARIES = 256
        const val MAX_SESSION_SUMMARIES = 128
    }

    private data class PendingSend(
        val sessionId: String,
        val summary: McpSessionSendSummary,
    )
}
