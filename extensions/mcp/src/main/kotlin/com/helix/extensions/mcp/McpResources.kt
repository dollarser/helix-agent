package com.helix.extensions.mcp

import kotlinx.serialization.json.JsonObject

/** Resources are remote data, never local filesystem handles or instructions. */
data class McpResourcePage(
    val entries: List<JsonObject>,
    val nextCursor: String?,
)

data class McpResourceText(
    val uri: String,
    val mimeType: String?,
    val text: String,
    val contentHash: String,
)

internal fun requireResourceUri(uri: String) {
    require(uri.length in 1..4096 && uri.none { it.isISOControl() }) { "Invalid MCP resource URI" }
    require(java.net.URI(uri).isAbsolute) { "MCP resource URI must be absolute" }
}

/** Public codes contain no server-controlled text, credentials, or resource contents. */
enum class McpResourceFailure {
    UNSUPPORTED,
    BINARY_UNSUPPORTED,
    URI_MISMATCH,
    NOT_DISCOVERED,
    MATCH_BUDGET_EXCEEDED,
}

class McpResourceException(
    val reason: McpResourceFailure,
) : IllegalArgumentException(reason.name)
