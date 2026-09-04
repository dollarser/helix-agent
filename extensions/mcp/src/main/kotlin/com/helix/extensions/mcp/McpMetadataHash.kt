package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal fun ToolSchema.sha256(maxBytes: Int): String {
    val canonical = toBoundedJson(maxBytes).canonicalJson()
    return canonical.toByteArray(Charsets.UTF_8).sha256()
}

internal fun ToolSchema.toBoundedJson(maxBytes: Int): JsonObject {
    val json = McpJson.encodeToJsonElement(ToolSchema.serializer(), this) as JsonObject
    val canonical = json.canonicalJson()
    val bytes = canonical.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "tool input schema exceeds $maxBytes bytes" }
    return json
}

internal fun hashFields(fields: List<String?>): String {
    val canonical =
        buildString {
            fields.forEach { field ->
                if (field == null) append("-1:") else append(field.toByteArray().size).append(':').append(field)
                append(';')
            }
        }
    return canonical.toByteArray(Charsets.UTF_8).sha256()
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun JsonElement.canonicalJson(): String =
    when (this) {
        is JsonObject -> {
            entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${JsonPrimitive(key)}:${value.canonicalJson()}"
            }
        }

        is JsonArray -> {
            joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
        }

        else -> {
            toString()
        }
    }
