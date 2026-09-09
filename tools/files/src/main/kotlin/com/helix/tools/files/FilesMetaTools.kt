package com.helix.tools.files

import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The `files.*` namespace of built-in file tools (roadmap HXA-042): FilesStatTool,
 * FilesListTool, FilesSearchTool (all READ_ONLY) and FilesMkdirTool (LOCAL_MUTATION).
 *
 * They share the model-safe addressing contract of the other file tools: the model references a
 * location ONLY by its FileScopePath reference and never sees a real path (doc 10). Each keeps
 * its own `register` so the short `files.*` name and the namespaced implementation bind to the
 * SAME descriptor (and therefore the same Policy) — there is one contract per name, not two.
 * (Plain block comment, not KDoc: it documents the file, not a declaration.)
 */

// ── files.stat ────────────────────────────────────────────────────────────────────────────

// ── files.list ────────────────────────────────────────────────────────────────────────────

// ── files.search ──────────────────────────────────────────────────────────────────────────

// ── files.mkdir ───────────────────────────────────────────────────────────────────────────

// ── shared, package-private helpers (kept tiny and side-effect free) ──────────────────────

internal fun filesMetaToolsParsePath(args: JsonObject): FileScopePath? {
    val ref = args["path"]?.jsonPrimitive?.content ?: return null
    return runCatching { FileScopePath.fromModelReference(ref) }.getOrNull()
}

internal fun filesMetaToolsIntArg(
    args: JsonObject,
    key: String,
): Int? = (args[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun filesMetaToolsStr(
    maxLength: Int,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("maxLength", JsonPrimitive(maxLength))
        description?.let { put("description", JsonPrimitive(it)) }
    }

internal fun filesMetaToolsIntObject(
    min: Int,
    max: Int,
    default: Int,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("minimum", JsonPrimitive(min))
        put("maximum", JsonPrimitive(max))
        put("default", JsonPrimitive(default))
        put("description", JsonPrimitive(description))
    }

internal fun filesMetaToolsBool(): JsonObject = buildJsonObject { put("type", JsonPrimitive("boolean")) }

internal fun filesMetaToolsInt(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

internal fun filesMetaToolsStringArray(maxItems: Int): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", JsonObject(mapOf("type" to JsonPrimitive("string"))))
        put("maxItems", JsonPrimitive(maxItems))
    }
