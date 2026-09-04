package com.helix.extensions.mcp

import kotlinx.serialization.json.JsonObject

data class McpToolResultLimits(
    val maxBlocks: Int = 64,
    val maxTextBytes: Int = 256 * 1024,
    val maxBinaryBytes: Int = 1024 * 1024,
    val maxStructuredBytes: Int = 512 * 1024,
    val maxTotalBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxBlocks in 1..256) { "maxBlocks must be 1..256" }
        require(maxTextBytes in 1..1_048_576) { "maxTextBytes must be 1..1048576" }
        require(maxBinaryBytes in 1..4_194_304) { "maxBinaryBytes must be 1..4194304" }
        require(maxStructuredBytes in 1..2_097_152) { "maxStructuredBytes must be 1..2097152" }
        require(maxTotalBytes in 1..8_388_608) { "maxTotalBytes must be 1..8388608" }
    }
}

data class McpToolResult(
    val isError: Boolean,
    val blocks: List<McpResultBlock>,
    val structuredContent: JsonObject?,
)

sealed interface McpResultBlock {
    data class Text(
        val text: String,
    ) : McpResultBlock

    data class Image(
        val mimeType: String,
        val base64Data: String,
    ) : McpResultBlock

    data class Audio(
        val mimeType: String,
        val base64Data: String,
    ) : McpResultBlock

    data class ResourceLink(
        val uri: String,
        val name: String,
        val title: String?,
        val description: String?,
        val mimeType: String?,
        val size: Long?,
    ) : McpResultBlock

    data class EmbeddedText(
        val uri: String,
        val mimeType: String?,
        val text: String,
    ) : McpResultBlock

    data class EmbeddedBlob(
        val uri: String,
        val mimeType: String?,
        val base64Data: String,
    ) : McpResultBlock
}
