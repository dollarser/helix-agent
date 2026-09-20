package com.helix.core.storage.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File

internal object SessionExportJson {
    /** Bound nesting before the tree parser sees untrusted persisted tool arguments or checkpoints. */
    fun objectOrNull(text: String): JsonObject? {
        if (text.toByteArray(Charsets.UTF_8).size > SessionExportFormat.LINE_BYTES || !boundedDepth(text)) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun rows(
        file: File,
        checkCancelled: () -> Unit,
        consume: (JsonObject) -> Unit,
    ) {
        file.inputStream().buffered(SessionExportFormat.COPY_BUFFER_BYTES).use { input ->
            val line = ByteArrayOutputStream()
            var total = 0L
            var next = input.read()
            while (next != -1) {
                require(++total <= SessionExportFormat.FILE_BYTES) { "Snapshot exceeds export limit" }
                if (next == '\n'.code) {
                    checkCancelled()
                    consume(
                        requireNotNull(objectOrNull(line.toString(Charsets.UTF_8.name()))) { "Invalid snapshot row" },
                    )
                    line.reset()
                } else {
                    require(line.size() < SessionExportFormat.LINE_BYTES - 1) { "Snapshot line exceeds export limit" }
                    line.write(next)
                }
                next = input.read()
            }
            require(line.size() == 0) { "Incomplete snapshot row" }
        }
    }

    private fun boundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && (char == '{' || char == '[') -> if (++depth > MAX_DEPTH) return false
                !quoted && (char == '}' || char == ']') -> depth--
            }
        }
        return true
    }

    private const val MAX_DEPTH = 64
}
