package com.helix.app.approval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Actual requested values for display; canonical authorization facts remain unchanged. */
internal object ApprovalArgumentPreview {
    fun text(arguments: String): String {
        val fields = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        val targets =
            fields?.filterKeys {
                it in setOf("path", "source", "destination", "url", "command", "packageName", "text", "query", "name")
            }
        val preview =
            targets?.values?.takeIf { it.isNotEmpty() }?.joinToString(" · ") {
                (it as? JsonPrimitive)?.content ?: it.toString()
            } ?: arguments
        return preview.replace('\n', ' ').replace('\r', ' ').take(240)
    }
}
