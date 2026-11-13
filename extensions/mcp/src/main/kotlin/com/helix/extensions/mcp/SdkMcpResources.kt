package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.ensureActive
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

    suspend fun read(uri: String): List<McpResourceContent> {
        requireResourceUri(uri)
        val result = client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri)))
        require(result.contents.size in 1..64) { "MCP resource content count must be 1..64" }
        var textBytes = 0L
        var binaryBytes = 0L
        return result.contents.map { content ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (content.uri != uri) throw McpResourceException(McpResourceFailure.URI_MISMATCH)
            require(content.mimeType == null || content.mimeType!!.length <= 256) { "MCP MIME type too large" }
            when (content) {
                is TextResourceContents -> {
                    textBytes += content.text.toByteArray(Charsets.UTF_8).size
                    require(textBytes <= 262144) { "MCP resource text exceeds 256 KiB" }
                    McpResourceText(content.uri, content.mimeType, content.text, hashFields(listOf(content.text)))
                }

                is BlobResourceContents -> {
                    val encoded = content.blob
                    require(encoded.length <= ((MAX_RESOURCE_BINARY_BYTES + 2) / 3 * 4)) {
                        "MCP resource binary exceeds 1 MiB"
                    }
                    val decoded =
                        java.util.Base64
                            .getDecoder()
                            .decode(encoded)
                    require(
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(decoded) == encoded,
                    ) {
                        "MCP resource blob is not canonical base64"
                    }
                    binaryBytes += decoded.size
                    require(binaryBytes <= MAX_RESOURCE_BINARY_BYTES) { "MCP resource binary exceeds 1 MiB" }
                    val hash =
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(decoded)
                            .joinToString("") { "%02x".format(it) }
                    McpResourceBlob(content.uri, content.mimeType, decoded, hash)
                }

                else -> {
                    error("Unsupported MCP resource content")
                }
            }
        }
    }
}

internal const val MAX_RESOURCE_BINARY_BYTES = 1024 * 1024
