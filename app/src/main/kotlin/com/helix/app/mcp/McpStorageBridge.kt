package com.helix.app.mcp

import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.McpCapabilityKind
import com.helix.core.storage.repository.McpCapabilityRepository
import com.helix.core.storage.repository.McpCapabilitySpec
import com.helix.core.storage.repository.McpHttpServerSpec
import com.helix.core.storage.repository.McpServerRepository
import com.helix.extensions.mcp.McpCredentialLookup
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.McpServerConfig
import java.security.MessageDigest

class McpStorageBridge(
    private val servers: McpServerRepository,
    private val capabilities: McpCapabilityRepository,
    private val credentialLookup: McpCredentialLookup,
    private val credentialDelete: (SecretAlias) -> Unit,
) {
    constructor(storage: HelixStorage) : this(
        servers = storage.mcpServers,
        capabilities = storage.mcpCapabilities,
        credentialLookup = McpCredentialLookup { alias -> storage.secrets.get(alias) },
        credentialDelete = storage.secrets::delete,
    )

    fun registerDisabled(
        id: String,
        endpoint: String,
        authAlias: String?,
    ): McpServerConfig {
        servers.registerHttp(McpHttpServerSpec(id, endpoint, authAlias))
        return load(id)
    }

    fun load(id: String): McpServerConfig {
        val entity = servers.resolve(id)
        require(entity.transport == STREAMABLE_HTTP && entity.commandRef == null) {
            "MCP server is not a Streamable HTTP configuration"
        }
        val endpoint = requireNotNull(entity.endpointRef) { "MCP HTTP endpoint is missing" }
        return McpServerConfig(
            id =
                com.helix.core.model
                    .McpServerId(entity.id),
            endpoint =
                com.helix.core.model.NormalizedEndpoint
                    .parse(endpoint),
            bearerSecretAlias = entity.authAlias?.let(::SecretAlias),
            enabled = entity.enabled,
        )
    }

    fun credentials(): McpCredentialLookup = credentialLookup

    fun delete(id: String) {
        val alias = servers.resolve(id).authAlias?.let(::SecretAlias)
        servers.delete(id)
        alias?.let(credentialDelete)
    }

    fun setServerEnabled(
        id: String,
        enabled: Boolean,
    ): McpServerConfig {
        val current = servers.resolve(id)
        servers.update(current, enabled, current.trustState)
        return load(id)
    }

    fun enabledToolNames(id: String): Set<String> =
        capabilities.listByServer(id).filter { it.kind == "tool" && it.enabled }.mapTo(linkedSetOf()) { it.name }

    fun setEnabledTools(
        id: String,
        names: Set<String>,
    ) {
        val rows = capabilities.listByServer(id)
        val available = rows.filter { it.kind == "tool" }.mapTo(mutableSetOf()) { it.name }
        require(names.all { it in available }) { "MCP tool selection contains an unknown tool" }
        rows.filter { it.kind == "tool" }.forEach { row ->
            capabilities.setEnabled(row.rowId, row.name in names)
        }
    }

    fun persistHandshake(snapshot: McpHandshakeSnapshot) {
        val current = load(snapshot.serverId.value)
        require(current.endpoint.full == snapshot.endpoint && current.endpoint.origin == snapshot.origin) {
            "MCP endpoint changed during handshake"
        }
        capabilities.replaceSnapshot(snapshot.serverId.value, snapshot.toCapabilitySpecs())
    }
}

internal fun McpHandshakeSnapshot.toCapabilitySpecs(): List<McpCapabilitySpec> {
    val protocol = identity.negotiatedProtocolVersion
    val capability =
        McpCapabilitySpec(
            protocolVersion = protocol,
            kind = McpCapabilityKind.CAPABILITY,
            name = "server-capabilities",
            contentHash =
                sha256(
                    listOf(
                        metadata.capabilities.tools,
                        metadata.capabilities.resources,
                        metadata.capabilities.prompts,
                        metadata.capabilities.toolListChanged,
                        metadata.capabilities.resourceListChanged,
                        metadata.capabilities.resourceSubscribe,
                        metadata.capabilities.promptListChanged,
                    ).joinToString(","),
                ),
        )
    val tools =
        metadata.tools.map { tool ->
            McpCapabilitySpec(protocol, McpCapabilityKind.TOOL, tool.name, tool.schemaHash)
        }
    val resources =
        metadata.resources.map { resource ->
            McpCapabilitySpec(protocol, McpCapabilityKind.RESOURCE, resource.uri, resource.metadataHash)
        }
    val prompts =
        metadata.prompts.map { prompt ->
            McpCapabilitySpec(protocol, McpCapabilityKind.PROMPT, prompt.name, prompt.metadataHash)
        }
    return listOf(capability) + tools + resources + prompts
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val STREAMABLE_HTTP = "streamable-http"
