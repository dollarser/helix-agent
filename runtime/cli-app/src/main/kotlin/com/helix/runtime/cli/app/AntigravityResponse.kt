package com.helix.runtime.cli.app

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

internal data class AntigravityDecoded(
    val events: List<ModelEvent>,
    val assistant: ModelMessage? = null,
    val originalParts: JsonArray? = null,
)

/** A complete response is validated before any executable tool call is published. */
internal object AntigravityResponse {
    fun decode(
        root: JsonObject,
        names: Map<String, String>,
    ): AntigravityDecoded {
        var assistant: ModelMessage? = null
        var original: JsonArray? = null
        val decoder =
            AntigravityStreamDecoder(names) { message, parts ->
                assistant = message
                original = parts
            }
        val events = decoder.feed("data: $root\n\n".toByteArray(Charsets.UTF_8)) + decoder.finish()
        return AntigravityDecoded(events, assistant, original)
    }

    fun usage(response: JsonObject): ModelEvent.Usage {
        val value = response["usageMetadata"] as? JsonObject
        val input = value?.get("promptTokenCount")?.jsonPrimitive?.longOrNull
        val output = value?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull
        val thoughts = value?.get("thoughtsTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
        return ModelEvent.Usage(input, output?.let { Math.addExact(it, thoughts) })
    }
}
