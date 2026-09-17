package com.helix.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model projection only. Full verified payloads remain in tool_results for UI/audit/recovery. */
internal object ToolModelResult {
    private val omitted =
        mapOf(
            "write" to setOf("usageBytesAfter", "encoding", "mimeType"),
            "edit" to setOf("usageBytesAfter"),
            "files.copy" to setOf("usageBytesAfter"),
            "files.move" to setOf("usageBytesAfter"),
            "files.delete" to setOf("usageBytesAfter"),
            "files.extract" to setOf("usageBytesAfter"),
        )

    @Suppress("ReturnCount") // Independent lossless fallbacks for unknown output formats.
    fun project(
        name: String,
        payload: String,
        reference: String? = null,
    ): String {
        if (reference != null && name != ToolResultReadTool.NAME && payload.toByteArray().size > MAX_INLINE_BYTES) {
            return buildJsonObject {
                put("truncated", true)
                put("resultRef", reference)
                put("readTool", ToolResultReadTool.NAME)
                put("totalCharacters", payload.codePointCount(0, payload.length))
                put("preview", payload.substring(0, payload.offsetByCodePoints(0, PREVIEW_CHARACTERS)))
                put("nextOffset", PREVIEW_CHARACTERS)
            }.toString()
        }
        val excluded = omitted[name].orEmpty()
        if (excluded.isEmpty() && name != "code.javascript.run") return payload
        val document =
            try {
                Json.parseToJsonElement(payload) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        // Never slice serialized JSON or recursively remove user content with matching keys.
        if (name == "code.javascript.run" && document != null) {
            val result = document["result"] as? JsonPrimitive
            val decoded =
                result?.takeIf { it.isString }?.let {
                    runCatching {
                        Json.parseToJsonElement(
                            it.content,
                        )
                    }.getOrNull()
                }
            if (decoded != null) return JsonObject(document - "outputBytes" + ("result" to decoded)).toString()
        }
        return document
            ?.let { JsonObject(it.filterKeys { key -> key !in excluded }).toString() }
            ?: payload
    }

    private const val MAX_INLINE_BYTES = 16_384
    private const val PREVIEW_CHARACTERS = 2048
}
