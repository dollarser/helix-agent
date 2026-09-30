package com.helix.runtime.cli.app

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Rejoin the application's separately persisted TEXT and TOOL_CALLS without duplicating signed text. */
internal fun antigravityHistory(
    messages: List<ModelMessage>,
    replay: (ModelMessage) -> JsonArray?,
    encode: (ModelMessage, JsonArray?) -> JsonObject,
): JsonArray {
    val encoded = mutableListOf<JsonObject>()
    var previous: ModelMessage? = null
    messages.filter { it.role != ModelRole.SYSTEM }.forEach { message ->
        val original = if (message.toolCalls.isNotEmpty()) replay(message) else null
        val text = original?.let(::antigravityVisibleText)
        val priorText = previous?.takeIf { it.role == ModelRole.ASSISTANT && it.toolCalls.isEmpty() }
        if (text != null && message.text.isEmpty() && priorText?.text == text) {
            encoded.removeAt(encoded.lastIndex)
        }
        encoded += encode(message, original)
        previous = message
    }
    return JsonArray(encoded)
}

internal fun antigravityVisibleText(parts: JsonArray): String =
    parts.joinToString("") { value ->
        val part = value.jsonObject
        val thought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
        val text = part["text"]?.jsonPrimitive?.content.orEmpty()
        if (thought) "" else text
    }
