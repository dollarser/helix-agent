package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SdkMcpResources(
    private val client: Client,
) {
    suspend fun list(
        cursor: String?,
        templates: Boolean,
    ): McpResourcePage {
        require(cursor == null || cursor.length <= 4096) { "MCP resource cursor too large" }
        val params = PaginatedRequestParams(cursor = cursor)
        val page =
            if (templates) {
                val result = client.listResourceTemplates(ListResourceTemplatesRequest(params))
                McpResourcePage(
                    result.resourceTemplates.map {
                        buildJsonObject {
                            put("uriTemplate", it.uriTemplate)
                            put("name", it.name)
                            it.description?.let { value -> put("description", value) }
                            it.mimeType?.let { value -> put("mimeType", value) }
                        }
                    },
                    result.nextCursor,
                )
            } else {
                val result = client.listResources(ListResourcesRequest(params))
                McpResourcePage(
                    result.resources.map {
                        requireResourceUri(it.uri)
                        buildJsonObject {
                            put("uri", it.uri)
                            put("name", it.name)
                            it.description?.let { value -> put("description", value) }
                            it.mimeType?.let { value -> put("mimeType", value) }
                            it.size?.let { value -> put("size", value) }
                        }
                    },
                    result.nextCursor,
                )
            }
        require(page.entries.size <= 128) { "MCP resource page exceeds 128 entries" }
        require(page.nextCursor == null || page.nextCursor.length <= 4096) { "MCP resource cursor too large" }
        require(
            page.entries.sumOf {
                it
                    .toString()
                    .toByteArray()
                    .size
                    .toLong()
            } <= 262144,
        ) {
            "MCP resource metadata exceeds 256 KiB"
        }
        return page
    }

    suspend fun read(uri: String): List<McpResourceText> {
        requireResourceUri(uri)
        val result = client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri)))
        require(result.contents.size in 1..64) { "MCP resource content count must be 1..64" }
        var bytes = 0L
        return result.contents.map { content ->
            if (content !is TextResourceContents) throw McpResourceException(McpResourceFailure.BINARY_UNSUPPORTED)
            if (content.uri != uri) throw McpResourceException(McpResourceFailure.URI_MISMATCH)
            require(content.mimeType == null || content.mimeType!!.length <= 256) { "MCP MIME type too large" }
            val textBytes = content.text.toByteArray(Charsets.UTF_8)
            bytes += textBytes.size
            require(bytes <= 262144) { "MCP resource text exceeds 256 KiB" }
            McpResourceText(content.uri, content.mimeType, content.text, hashFields(listOf(content.text)))
        }
    }
}
