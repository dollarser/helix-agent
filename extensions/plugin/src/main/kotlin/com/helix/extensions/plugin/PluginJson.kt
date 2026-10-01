package com.helix.extensions.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Bounded syntax parsing only; field semantics and component failure boundaries are separate. */
internal object PluginJson {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_DEPTH = 32

    fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..MAX_BYTES) { "PLUGIN_JSON_SIZE" }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (invalid: CharacterCodingException) {
                throw IllegalArgumentException("PLUGIN_JSON_UTF8", invalid)
            }
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            when {
                escaped -> escaped = false
                char == '\\' && quoted -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && char in "[{" -> require(++depth <= MAX_DEPTH) { "PLUGIN_JSON_DEPTH" }
                !quoted && char in "]}" -> depth--
            }
        }
        return Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("PLUGIN_JSON_OBJECT")
    }

    fun string(
        root: JsonObject,
        field: String,
    ): String {
        val value = root[field] as? JsonPrimitive
        require(value?.isString == true) { "PLUGIN_STRING_REQUIRED:$field" }
        return value.content
    }
}
