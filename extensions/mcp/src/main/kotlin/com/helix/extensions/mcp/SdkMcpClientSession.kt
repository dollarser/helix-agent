package com.helix.extensions.mcp

import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

internal class SdkMcpClientSession(
    private val sdkClient: Client,
    private val responseWitness: McpResponseWitness,
    private val httpClient: HttpClient,
    private val capabilities: ServerCapabilities,
    private val identity: McpServerIdentity,
) : McpClientSession {
    private val closed = AtomicBoolean(false)

    override val server: McpServerIdentity
        get() = identity

    override suspend fun ping() {
        check(!closed.get()) { "MCP session is closed" }
        responseWitness.observe("ping") { sdkClient.ping(it) }
    }

    override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot {
        check(!closed.get()) { "MCP session is closed" }
        val toolsResult =
            capabilities.tools?.let {
                responseWitness.observe("tools/list") { sdkClient.listTools(options = it) }
            }
        val resourcesResult =
            capabilities.resources?.let {
                responseWitness.observe("resources/list") { sdkClient.listResources(options = it) }
            }
        val promptsResult =
            capabilities.prompts?.let {
                responseWitness.observe("prompts/list") { sdkClient.listPrompts(options = it) }
            }

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
        val result =
            responseWitness.observe("tools/call") {
                sdkClient.callTool(name, arguments.mapValues { (_, value) -> value.toSdkValue() }, options = it)
            }
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

    private fun resources(options: io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions): SdkMcpResources {
        check(!closed.get()) { "MCP session is closed" }
        if (capabilities.resources == null) throw McpResourceException(McpResourceFailure.UNSUPPORTED)
        return SdkMcpResources(sdkClient, options)
    }

    override suspend fun listResources(cursor: String?): McpResourcePage =
        responseWitness.observe("resources/list") { resources(it).list(cursor, false) }

    override suspend fun listResourceTemplates(cursor: String?): McpResourcePage =
        responseWitness.observe("resources/templates/list") { resources(it).list(cursor, true) }

    override suspend fun readResource(uri: String): List<McpResourceContent> =
        responseWitness.observe("resources/read") { resources(it).read(uri) }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            sdkClient.close()
        } finally {
            httpClient.close()
        }
    }
}
