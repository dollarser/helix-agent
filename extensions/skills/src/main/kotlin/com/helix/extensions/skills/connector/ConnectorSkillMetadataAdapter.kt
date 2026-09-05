package com.helix.extensions.skills.connector

import com.helix.extensions.skills.InvalidSkillException
import com.helix.extensions.skills.SkillLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Keep original bytes alongside a standard string-metadata projection; standalone Skill validation stays strict. */
internal object ConnectorSkillMetadataAdapter {
    const val ORIGINAL = "references/helix-import/original-SKILL.md.txt"

    @Suppress("ReturnCount") // invalid, absent and already-standard metadata preserve input for the existing validator
    fun adapt(
        files: Map<String, ByteArray>,
        diagnostics: MutableList<String>,
        name: String,
    ): Map<String, ByteArray> {
        val original = files.getValue("SKILL.md")
        val (fields, body) =
            try {
                SkillLoader().importFrontmatter(original)
            } catch (_: InvalidSkillException) {
                // Preserve invalid input for the normal installation validator to reject, never bless it here.
                return files
            }
        val metadata = fields["metadata"] as? Map<*, *> ?: return files
        if (metadata.values.all { it is String }) return files
        require(ORIGINAL !in files) { "CONNECTOR_METADATA_BACKUP_COLLISION" }
        val projected =
            metadata.entries.associate { (key, value) ->
                require(key is String) { "CONNECTOR_INVALID_METADATA_KEY" }
                key to JsonPrimitive(if (value is String) value else json(value).toString())
            }
        val normalized =
            JsonObject(
                fields.mapValues { (key, value) ->
                    if (key ==
                        "metadata"
                    ) {
                        JsonObject(projected)
                    } else {
                        json(value)
                    }
                },
            )
        val bytes = "---\n$normalized\n---\n$body".toByteArray(Charsets.UTF_8)
        require(bytes.size <= SkillLoader.MAX_SKILL_BYTES) { "CONNECTOR_METADATA_TOO_LARGE" }
        diagnostics += "SKILL_METADATA_NORMALIZED:$name"
        (metadata["requires"] as? Map<*, *>)?.get("bins")?.let { bins ->
            if (bins is List<*>) {
                bins.filterIsInstance<String>().forEach { bin ->
                    diagnostics += "SKILL_REQUIRES_BINARY:$name:${bin.take(128)}"
                }
            }
        }
        return files + mapOf("SKILL.md" to bytes, ORIGINAL to original.copyOf())
    }

    private fun json(value: Any?): JsonElement =
        when (value) {
            null -> {
                JsonNull
            }

            is String -> {
                JsonPrimitive(value)
            }

            is Boolean -> {
                JsonPrimitive(value)
            }

            is Number -> {
                JsonPrimitive(value)
            }

            is List<*> -> {
                JsonArray(value.map(::json))
            }

            is Map<*, *> -> {
                JsonObject(
                    value.entries.associate { (key, item) ->
                        require(key is String)
                        key to
                            json(item)
                    },
                )
            }

            else -> {
                error("CONNECTOR_INVALID_METADATA_VALUE")
            }
        }
}
