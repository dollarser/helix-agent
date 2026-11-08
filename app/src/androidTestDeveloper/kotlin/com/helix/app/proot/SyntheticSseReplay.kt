package com.helix.app.proot

import com.helix.core.model.ModelEvent
import com.helix.core.policy.DiagnosticRedaction
import com.helix.provider.openai.chat.ChatCompletionsStreamDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Production decoder replay of observed bytes, not interception of actual emitted events. */
internal fun syntheticDecoderReplay(
    bytes: ByteArray,
    lengths: List<Int>,
    complete: Boolean,
): JsonObject {
    val decoder = ChatCompletionsStreamDecoder()
    var offset = 0
    val events = mutableListOf<ModelEvent>()
    lengths.forEach { length ->
        events += decoder.feed(bytes.copyOfRange(offset, offset + length))
        offset += length
    }
    // Do not invent EOF/terminal events for a truncated capture.
    if (complete) events += decoder.finish()
    val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
    return buildJsonObject {
        put("kind", "production decoder offline replay; not actual event interception")
        put("protocolEnded", decoder.protocolEnded)
        decoder.failure?.let { put("failure", DiagnosticRedaction.redact(it)) }
        put("eventTypes", JsonArray(events.map { JsonPrimitive(it.javaClass.simpleName) }))
        put(
            "completedReasons",
            JsonArray(
                events.filterIsInstance<ModelEvent.Completed>().map {
                    JsonPrimitive(it.finishReason)
                },
            ),
        )
        put(
            "toolStarts",
            JsonArray(
                events.filterIsInstance<ModelEvent.ToolCallStarted>().map {
                    buildJsonObject {
                        put("index", it.index)
                        put("name", DiagnosticRedaction.redact(it.name))
                    }
                },
            ),
        )
        put("text", DiagnosticRedaction.redact(text))
        put("textSha256", sseHash(text.toByteArray(Charsets.UTF_8)))
        put("textCodePoints", text.codePointCount(0, text.length))
    }
}
