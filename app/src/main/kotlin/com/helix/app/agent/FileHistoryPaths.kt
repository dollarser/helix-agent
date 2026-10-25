package com.helix.app.agent

import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request-only projection of known file fields. Durable history and summary sources stay anchored. */
internal object FileHistoryPaths {
    private val tools =
        setOf(
            "read",
            "view_image",
            "write",
            "edit",
            "files.list",
            "files.stat",
            "glob",
            "grep",
            "files.mkdir",
            "files.copy",
            "files.move",
            "files.delete",
            "files.archive",
            "files.extract",
            "documents.inspect",
        )
    private val pathKeys = setOf("path", "source", "destination")

    fun project(
        name: String,
        json: String,
        directory: FileScopePath?,
        result: Boolean = false,
    ): String {
        val fields =
            if (directory != null && name in tools) {
                runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            } else {
                null
            }
        if (fields == null || directory == null) return json

        val projected =
            JsonObject(
                fields.mapValues { (key, value) ->
                    when {
                        key in pathKeys -> {
                            path(value, directory)
                        }

                        result && name in setOf("glob", "grep") && key == "matches" && value is JsonArray -> {
                            JsonArray(
                                value.map { match ->
                                    val row = match as? JsonObject
                                    row?.takeIf { "path" in it }?.let {
                                        JsonObject(it + ("path" to path(it.getValue("path"), directory)))
                                    } ?: match
                                },
                            )
                        }

                        else -> {
                            value
                        }
                    }
                },
            )
        return if (projected == fields) json else projected.toString()
    }

    private fun path(
        value: JsonElement,
        directory: FileScopePath,
    ): JsonElement {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ref = raw?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        return ref?.let { JsonPrimitive(it.referenceRelativeTo(directory)) } ?: value
    }
}
