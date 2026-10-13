package com.helix.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Message-level text bound by the original model-response commit, never a tool argument. */
internal object AssistantToolResponseContent {
    private const val FIELD = "assistantText"

    fun attachText(
        content: String,
        text: String,
    ): String {
        if (text.isBlank()) return content
        val array =
            Json.parseToJsonElement(content) as? JsonArray
                ?: throw IllegalArgumentException("a tool-call step must be an array")
        require(array.isNotEmpty()) { "a tool-call step must carry at least one call" }
        require(array.none { (it as? JsonObject)?.containsKey(FIELD) == true }) {
            "assistant text is owned by the model response commit"
        }
        val first =
            array.first() as? JsonObject
                ?: throw IllegalArgumentException("a tool-call element must be an object")
        return JsonArray(listOf(JsonObject(first + (FIELD to JsonPrimitive(text)))) + array.drop(1)).toString()
    }

    fun text(array: JsonArray): String {
        require(array.drop(1).none { (it as? JsonObject)?.containsKey(FIELD) == true }) {
            "assistant text must belong to the response, not a later call"
        }
        val first = array.first() as JsonObject
        if (FIELD !in first) return ""
        return (first[FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("assistant text must be a string")
    }
}
