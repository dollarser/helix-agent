package com.helix.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

    fun project(
        name: String,
        payload: String,
    ): String {
        val excluded = omitted[name] ?: return payload
        val document =
            try {
                Json.parseToJsonElement(payload) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return payload
        // Never slice serialized JSON or recursively remove user content with matching keys.
        return JsonObject(document.filterKeys { it !in excluded }).toString()
    }
}
