package com.helix.extensions.mcp

import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

internal class SdkMcpClientSession(
    private val sdkClient: Client,
    private val httpClient: HttpClient,
    private val capabilities: ServerCapabilities,
    private val identity: McpServerIdentity,
) : McpClientSession {
    private val closed = AtomicBoolean(false)

    override val server: McpServerIdentity
        get() = identity

    override suspend fun ping() {
        check(!closed.get()) { "MCP session is closed" }
        sdkClient.ping()
    }

    override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot {
        check(!closed.get()) { "MCP session is closed" }
        val toolsResult = capabilities.tools?.let { sdkClient.listTools() }
        val resourcesResult = capabilities.resources?.let { sdkClient.listResources() }
        val promptsResult = capabilities.prompts?.let { sdkClient.listPrompts() }

        return McpMetadataSnapshot(
            capabilities = capabilities.toSnapshot(),
            tools = toolsResult?.tools.orEmpty().toToolMetadata(limits),
            resources = resourcesResult?.resources.orEmpty().toResourceMetadata(limits),
            prompts = promptsResult?.prompts.orEmpty().toPromptMetadata(limits),
            toolsHaveMore =
                toolsResult != null &&
                    (toolsResult.nextCursor != null || toolsResult.tools.size > limits.maxItemsPerKind),
            resourcesHaveMore =
                resourcesResult != null &&
                    (resourcesResult.nextCursor != null || resourcesResult.resources.size > limits.maxItemsPerKind),
            promptsHaveMore =
                promptsResult != null &&
                    (promptsResult.nextCursor != null || promptsResult.prompts.size > limits.maxItemsPerKind),
        ).also { snapshot -> snapshot.requireWithinTotalBytes(limits.maxTotalMetadataBytes) }
    }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
        limits: McpToolResultLimits,
    ): McpToolResult {
        check(!closed.get()) { "MCP session is closed" }
        require(name.isNotBlank()) { "MCP tool name must not be blank" }
        val result = sdkClient.callTool(name, arguments.mapValues { (_, value) -> value.toSdkValue() })
        require(result.content.size <= limits.maxBlocks) {
            "MCP tool result exceeds ${limits.maxBlocks} blocks"
        }
        val blocks = result.content.map { block -> block.toHelixBlock(limits) }
        val structured = result.structuredContent
        if (structured != null) {
            require(structured.toString().toByteArray(Charsets.UTF_8).size <= limits.maxStructuredBytes) {
                "MCP structured result exceeds ${limits.maxStructuredBytes} bytes"
            }
        }
        val totalBytes =
            blocks.sumOf { it.payloadBytes() } +
                (structured?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        require(totalBytes <= limits.maxTotalBytes) {
            "MCP tool result exceeds ${limits.maxTotalBytes} total bytes"
        }
        return McpToolResult(result.isError == true, blocks, structured)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            sdkClient.close()
        } finally {
            httpClient.close()
        }
    }
}
