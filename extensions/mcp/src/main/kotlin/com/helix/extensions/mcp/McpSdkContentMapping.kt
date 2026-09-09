package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonElement.toSdkValue(): Any? =
    when (this) {
        JsonNull -> {
            null
        }

        is JsonObject -> {
            mapValues { (_, value) -> value.toSdkValue() }
        }

        is JsonArray -> {
            map { it.toSdkValue() }
        }

        is JsonPrimitive -> {
            if (isString) {
                content
            } else {
                booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
            }
        }
    }

internal fun io.modelcontextprotocol.kotlin.sdk.types.ContentBlock.toHelixBlock(
    limits: McpToolResultLimits,
): McpResultBlock =
    when (this) {
        is TextContent -> {
            McpResultBlock.Text(text.requireBytes("text", limits.maxTextBytes))
        }

        is ImageContent -> {
            McpResultBlock.Image(
                mimeType.requireBytes("image MIME type", MAX_RESULT_LABEL_BYTES),
                data.requireBase64Bytes("image", limits.maxBinaryBytes),
            )
        }

        is AudioContent -> {
            McpResultBlock.Audio(
                mimeType.requireBytes("audio MIME type", MAX_RESULT_LABEL_BYTES),
                data.requireBase64Bytes("audio", limits.maxBinaryBytes),
            )
        }

        is ResourceLink -> {
            McpResultBlock.ResourceLink(
                uri = uri.requireBytes("resource URI", MAX_RESULT_URI_BYTES),
                name = name.requireBytes("resource name", MAX_RESULT_LABEL_BYTES),
                title = title?.requireBytes("resource title", MAX_RESULT_LABEL_BYTES),
                description = description?.requireBytes("resource description", limits.maxTextBytes),
                mimeType = mimeType?.requireBytes("resource MIME type", MAX_RESULT_LABEL_BYTES),
                size = size,
            )
        }

        is EmbeddedResource -> {
            when (val contents = resource) {
                is TextResourceContents -> {
                    McpResultBlock.EmbeddedText(
                        uri = contents.uri.requireBytes("embedded resource URI", MAX_RESULT_URI_BYTES),
                        mimeType = contents.mimeType?.requireBytes("embedded MIME type", MAX_RESULT_LABEL_BYTES),
                        text = contents.text.requireBytes("embedded text", limits.maxTextBytes),
                    )
                }

                is BlobResourceContents -> {
                    McpResultBlock.EmbeddedBlob(
                        uri = contents.uri.requireBytes("embedded resource URI", MAX_RESULT_URI_BYTES),
                        mimeType = contents.mimeType?.requireBytes("embedded MIME type", MAX_RESULT_LABEL_BYTES),
                        base64Data = contents.blob.requireBase64Bytes("embedded blob", limits.maxBinaryBytes),
                    )
                }

                else -> {
                    error("unsupported MCP embedded resource type: ${contents::class.simpleName}")
                }
            }
        }

        else -> {
            error("unsupported MCP result block type: ${this::class.simpleName}")
        }
    }

internal fun McpResultBlock.payloadBytes(): Int =
    when (this) {
        is McpResultBlock.Text -> {
            text.toByteArray(Charsets.UTF_8).size
        }

        is McpResultBlock.Image -> {
            mimeType.length + base64Data.length
        }

        is McpResultBlock.Audio -> {
            mimeType.length + base64Data.length
        }

        is McpResultBlock.ResourceLink -> {
            listOfNotNull(uri, name, title, description, mimeType).sumOf { it.toByteArray(Charsets.UTF_8).size }
        }

        is McpResultBlock.EmbeddedText -> {
            uri.toByteArray(Charsets.UTF_8).size +
                (mimeType?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
                text.toByteArray(Charsets.UTF_8).size
        }

        is McpResultBlock.EmbeddedBlob -> {
            uri.length + (mimeType?.length ?: 0) + base64Data.length
        }
    }

internal fun String.requireBytes(
    label: String,
    maxBytes: Int,
): String {
    require(toByteArray(Charsets.UTF_8).size <= maxBytes) { "MCP $label exceeds $maxBytes bytes" }
    return this
}

internal fun String.requireBase64Bytes(
    label: String,
    maxDecodedBytes: Int,
): String {
    require(length <= ((maxDecodedBytes.toLong() + 2) / 3 * 4)) {
        "MCP $label exceeds $maxDecodedBytes decoded bytes"
    }
    require(matches(BASE64_PATTERN)) { "MCP $label is not canonical base64 data" }
    return this
}

private val BASE64_PATTERN = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
private const val MAX_RESULT_LABEL_BYTES = 4_096
private const val MAX_RESULT_URI_BYTES = 16_384
