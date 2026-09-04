package com.helix.extensions.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Helix-owned boundary around the pinned MCP SDK.
 *
 * Public callers see only these stable values. SDK, Ktor, transport, and wire DTO types stay
 * inside this module so a failed Android Spike can replace the implementation without changing
 * Agent Core or the Tool framework.
 */
interface McpClientFacade {
    suspend fun connect(endpointUrl: String): McpClientSession
}

interface McpClientSession {
    val server: McpServerIdentity

    suspend fun ping()

    suspend fun snapshotMetadata(limits: McpMetadataLimits = McpMetadataLimits()): McpMetadataSnapshot

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
        limits: McpToolResultLimits = McpToolResultLimits(),
    ): McpToolResult

    suspend fun close()
}

data class McpServerIdentity(
    val name: String,
    val version: String,
    val negotiatedProtocolVersion: String,
)

object McpClients {
    fun sdk(
        clientName: String,
        clientVersion: String,
    ): McpClientFacade {
        require(clientName.isNotBlank()) { "clientName must not be blank" }
        require(clientVersion.isNotBlank()) { "clientVersion must not be blank" }
        return SdkMcpClientFacade(clientName, clientVersion)
    }
}
