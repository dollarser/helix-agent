package com.helix.extensions.mcp

import kotlinx.serialization.json.JsonObject

/** Resources are remote data, never local filesystem handles or instructions. */
data class McpResourcePage(
    val entries: List<JsonObject>,
    val nextCursor: String?,
)

sealed interface McpResourceContent {
    val uri: String
    val mimeType: String?
    val contentHash: String
}

data class McpResourceBlob(
    override val uri: String,
    override val mimeType: String?,
    val bytes: ByteArray,
    override val contentHash: String,
) : McpResourceContent

data class McpResourceText(
    override val uri: String,
    override val mimeType: String?,
    val text: String,
    override val contentHash: String,
) : McpResourceContent

internal fun requireResourceUri(uri: String) {
    require(uri.length in 1..4096 && uri.none { it.isISOControl() }) { "Invalid MCP resource URI" }
    require(java.net.URI(uri).isAbsolute) { "MCP resource URI must be absolute" }
}

/** Public codes contain no server-controlled text, credentials, or resource contents. */
enum class McpResourceFailure {
    UNSUPPORTED,
    BINARY_PUBLISHER_UNAVAILABLE,
    URI_MISMATCH,
    NOT_DISCOVERED,
    MATCH_BUDGET_EXCEEDED,
}

class McpResourceException(
    val reason: McpResourceFailure,
) : IllegalArgumentException(reason.name)
