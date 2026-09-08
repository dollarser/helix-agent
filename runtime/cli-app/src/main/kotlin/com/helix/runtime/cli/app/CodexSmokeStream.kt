package com.helix.runtime.cli.app

import com.helix.runtime.cli.app.CodexSubscriptionSmoke.Companion.EXPECTED_TEXT
import com.helix.runtime.cli.app.CodexSubscriptionSmoke.Companion.MAX_STREAM_BYTES
import com.helix.runtime.cli.app.CodexSubscriptionSmoke.Companion.MAX_TEXT_CHARS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.BufferedSource

internal object CodexSmokeStream {
    fun read(source: BufferedSource): String {
        val bytes = readBounded(source)
        val text = StringBuilder()
        var completed = false
        bytes.decodeToString().lineSequence().mapNotNull(::parseEvent).forEach { event ->
            completed = accept(event, text) || completed
        }
        if (!completed) throw CodexSmokeException("response-incomplete")
        val normalized = text.toString().trim()
        if (normalized != EXPECTED_TEXT) throw CodexSmokeException("unexpected-output")
        return normalized
    }

    private fun readBounded(source: BufferedSource): ByteArray =
        try {
            source.readBoundedByteArray(MAX_STREAM_BYTES)
        } catch (_: IllegalArgumentException) {
            throw CodexSmokeException("response-too-large")
        }

    private fun parseEvent(line: String): JsonObject? {
        val data = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
        return if (data.isNullOrEmpty() || data == "[DONE]") {
            null
        } else {
            runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                ?: throw CodexSmokeException("response-protocol")
        }
    }

    private fun accept(
        event: JsonObject,
        text: StringBuilder,
    ): Boolean =
        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "response.output_text.delta" -> {
                appendDelta(event, text)
                false
            }

            "response.completed" -> {
                val response = event["response"] as? JsonObject
                if ((response?.get("status") as? JsonPrimitive)?.contentOrNull != "completed") {
                    throw CodexSmokeException("response-terminal")
                }
                true
            }

            "response.failed", "response.incomplete", "error" -> {
                throw CodexSmokeException("response-terminal")
            }

            else -> {
                false
            }
        }

    private fun appendDelta(
        event: JsonObject,
        text: StringBuilder,
    ) {
        val delta =
            (event["delta"] as? JsonPrimitive)?.contentOrNull
                ?: throw CodexSmokeException("response-protocol")
        text.append(delta)
        if (text.length > MAX_TEXT_CHARS) throw CodexSmokeException("output-too-large")
    }
}
