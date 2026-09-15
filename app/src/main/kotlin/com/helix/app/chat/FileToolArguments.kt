package com.helix.app.chat

import com.helix.core.model.ModelToolSchema
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Relative paths are bound before canonical hashing, approval and dispatch. */
internal object FileToolArguments {
    private val names =
        setOf(
            "read",
            "write",
            "edit",
            "files.list",
            "files.stat",
            "files.search",
            "files.mkdir",
            "files.copy",
            "files.move",
            "files.delete",
            "files.archive",
            "files.extract",
        )
    private val pathKeys = setOf("path", "source", "destination")
    private const val PATH_HELP =
        "Relative to the current session working directory (e.g. notes.txt, src/main.kt, or .). " +
            "Explicit scope:<id>:<relativePath> is also accepted. Never use Android absolute paths or ../ escapes."

    fun handles(descriptor: ToolDescriptor?): Boolean =
        descriptor != null && descriptor.origin == ToolOrigin.BuiltInOrigin && descriptor.name.value in names

    fun directory(
        scopeId: String,
        reference: String?,
    ): FileScopePath = reference?.let(FileScopePath::fromModelReference) ?: FileScopePath(scopeId, "")

    fun normalize(
        args: JsonObject,
        directory: FileScopePath,
    ): JsonObject {
        fun path(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
            val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
            val resolved =
                if (raw.startsWith("scope:")) {
                    FileScopePath.fromModelReference(raw)
                } else {
                    // Validate BEFORE joining, so ../ never climbs out of the selected directory.
                    val relative = FileScopePath(directory.scopeId, raw).relativePath
                    val joined = listOf(directory.relativePath, relative).filter { it.isNotEmpty() }.joinToString("/")
                    FileScopePath(directory.scopeId, joined)
                }
            return JsonPrimitive(resolved.toModelReference())
        }
        return JsonObject(
            args.mapValues { (key, value) ->
                when {
                    key in pathKeys -> path(value)
                    key == "sources" && value is JsonArray -> JsonArray(value.map(::path))
                    else -> value
                }
            },
        )
    }

    fun modelSchema(descriptor: ToolDescriptor): ModelToolSchema {
        if (!handles(descriptor)) {
            return ModelToolSchema(descriptor.name, descriptor.description, descriptor.inputSchema.toString())
        }
        val schema = descriptor.inputSchema
        val updated = withPathDescriptions(schema)
        val purpose = purposeFor(descriptor.name.value, descriptor.description)
        return ModelToolSchema(
            descriptor.name,
            "$purpose $PATH_HELP .helix internals are not writable.",
            JsonObject(schema + ("properties" to updated)).toString(),
        )
    }

    private fun withPathDescriptions(schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(
            properties.mapValues { (key, value) ->
                if (key in pathKeys && value is JsonObject) {
                    JsonObject(value + ("description" to JsonPrimitive(PATH_HELP)))
                } else {
                    value
                }
            },
        )
    }

    private fun purposeFor(
        name: String,
        fallback: String,
    ): String =
        when (name) {
            "write" -> {
                "Create a UTF-8 file with path and content. Existing files require explicit overwrite; " +
                    "omit expectedSha256 for new files."
            }

            "read" -> {
                "Read a file before editing; use its returned content and hash rather than guessing."
            }

            "edit" -> {
                "Edit an existing file; read first and supply the required matching content/hash."
            }

            "files.list" -> {
                "List immediate directory children; path '.' lists the current working directory."
            }

            "files.stat" -> {
                "Inspect file or directory metadata without reading its full content."
            }

            "files.search" -> {
                "Search within a directory using the declared query and result limits."
            }

            "files.mkdir" -> {
                "Create a directory at the desired relative path; follow existing-directory rules."
            }

            "files.copy" -> {
                "Copy source to destination; existing destinations require explicit overwrite."
            }

            "files.move" -> {
                "Move source to destination; existing destinations require explicit overwrite."
            }

            "files.delete" -> {
                "Move the selected user file or directory to trash; never delete the whole workspace."
            }

            "files.archive" -> {
                "Archive source into destination; the archive destination must be within work/."
            }

            "files.extract" -> {
                "Extract source into destination within work/; existing files require explicit overwrite."
            }

            else -> {
                fallback
            }
        }
}
