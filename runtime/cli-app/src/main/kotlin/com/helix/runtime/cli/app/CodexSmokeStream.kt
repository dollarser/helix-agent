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
        val text = StringBuilder()
        var remaining = MAX_STREAM_BYTES
        while (remaining > 0) {
            val newline = source.indexOf('\n'.code.toByte(), 0, remaining + 1)
            if (newline < 0 && source.buffer.size > remaining) responseTooLarge()
            val count = if (newline >= 0) newline + 1 else source.buffer.size
            if (count == 0L) break
            if (count > remaining) responseTooLarge()
            remaining -= count
            val event = parseEvent(source.readUtf8(count).trimEnd('\n', '\r'))
            if (event != null && accept(event, text)) {
                // SSE completion is the end of this request; the server need not close the socket.
                val normalized = text.toString().trim()
                if (normalized != EXPECTED_TEXT) throw CodexSmokeException("unexpected-output")
                return normalized
            }
        }
        if (remaining == 0L) responseTooLarge()
        throw CodexSmokeException("response-incomplete")
    }

    private fun responseTooLarge(): Nothing = throw CodexSmokeException("response-too-large")

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
