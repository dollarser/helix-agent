package com.helix.app.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Explicit versioned metadata, independent of compiler-generated serializers. */
internal object FileLibraryCodec {
    fun decode(raw: String): FileLibrary.Snapshot {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        return FileLibrary.Snapshot(recent = entries(root, "recent"), favorites = entries(root, "favorites"))
    }

    private fun entries(
        root: JsonObject,
        key: String,
    ): List<FileLibrary.Entry> =
        root.getValue(key).jsonArray.map { value ->
            val row = value.jsonObject
            FileLibrary.Entry(
                row.getValue("reference").jsonPrimitive.content,
                row.getValue("directory").jsonPrimitive.boolean,
            )
        }

    fun encode(snapshot: FileLibrary.Snapshot): String =
        buildJsonObject {
            put("version", snapshot.version)
            put("recent", encodeEntries(snapshot.recent))
            put("favorites", encodeEntries(snapshot.favorites))
        }.toString()

    private fun encodeEntries(entries: List<FileLibrary.Entry>): JsonArray =
        JsonArray(
            entries.map { row ->
                buildJsonObject {
                    put("reference", row.reference)
                    put("directory", row.directory)
                }
            },
        )
}
