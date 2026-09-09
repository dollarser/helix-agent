package com.helix.tools.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The mutation half of the `files.*` namespace (roadmap HXA-043): FilesCopyTool, FilesMoveTool
 * and FilesDeleteTool.
 *
 * Shared contract with the rest of the file tools: model-safe FileScopePath references only (the
 * real path never appears in arguments, output, or failure text — doc 10), sanitized stable
 * failure strings, a cancel signal that short-circuits to `Cancelled`, and region admission
 * (only `input/`, `work/`, `output/` are addressable; `.helix/` internals are not).
 *
 * The conflict policy is EXPLICIT (roadmap HXA-043): an existing destination — a file OR a
 * directory — is refused unless `overwrite` is set, and an `overwrite` into a directory is
 * refused regardless. Deleting never erases: `files.delete` moves the file into the scope's
 * `.helix/trash/` (restore and physical purge are separate store operations, HXA-043).
 *
 * All three are baseRisk L2 with per-call approval: the roadmap's "cross-scope and overwrite
 * raise the risk" rule is satisfied fail-closed — a call that could be lower-risk still pays
 * the L2 approval (see the HXA-043 completion record, 决策记录).
 * (Plain block comment, not KDoc: it documents the file, not a declaration.)
 */

/** The layout region a path lives in, when it is a user region; null otherwise. */
internal fun filesMutateToolsUserRegionOf(path: FileScopePath): String? {
    val region = WorkspaceLayout.regionOf(path.relativePath)
    return if (region != null && WorkspaceLayout.isRegion(region)) region else null
}

/** A model reference argument, or null when absent or malformed (fail-closed, sanitized). */
internal fun filesMutateToolsRefArg(
    args: JsonObject,
    key: String,
): FileScopePath? =
    args[key]?.jsonPrimitive?.content?.let {
        runCatching { FileScopePath.fromModelReference(it) }.getOrNull()
    }

/** A boolean argument that accepts both real booleans and the strings "true"/"false". */
internal fun filesMutateToolsBoolArg(
    args: JsonObject,
    key: String,
): Boolean =
    (args[key] as? JsonPrimitive)?.let { p ->
        if (p.isString) {
            p.content.toBooleanStrictOrNull()
        } else {
            when (p.content) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    } ?: false

internal fun filesMutateToolsStrSchema(
    maxLength: Int?,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        description?.let { put("description", JsonPrimitive(it)) }
    }

internal fun filesMutateToolsBoolSchema(description: String?): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("default", JsonPrimitive(false))
        description?.let { put("description", JsonPrimitive(it)) }
    }

internal fun filesMutateToolsIntSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

/** The shared `source`/`destination`/`overwrite` contract of `files.copy` and `files.move`. */
internal fun filesMutateToolsCopyMoveInputSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "source",
                    filesMutateToolsStrSchema(
                        512,
                        "Model reference of the file to copy: scope:<scopeId>:<relativePath>",
                    ),
                )
                put(
                    "destination",
                    filesMutateToolsStrSchema(512, "Model reference of the target: scope:<scopeId>:<relativePath>"),
                )
                put(
                    "overwrite",
                    filesMutateToolsBoolSchema("Replace the destination when it exists (default: refuse)"),
                )
            },
        )
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("source"), JsonPrimitive("destination"))),
        )
        put("additionalProperties", JsonPrimitive(false))
    }

/** The shared result contract of `files.copy` and `files.move`. */
internal fun filesMutateToolsCopyMoveOutputSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("source", filesMutateToolsStrSchema(512, null))
                put("destination", filesMutateToolsStrSchema(512, null))
                put("sizeBytes", filesMutateToolsIntSchema())
                put("sha256", filesMutateToolsStrSchema(64, null))
                put("overwritten", filesMutateToolsBoolSchema(null))
                put("usageBytesAfter", filesMutateToolsIntSchema())
            },
        )
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("source"),
                    JsonPrimitive("destination"),
                    JsonPrimitive("sizeBytes"),
                    JsonPrimitive("sha256"),
                    JsonPrimitive("overwritten"),
                    JsonPrimitive("usageBytesAfter"),
                ),
            ),
        )
        put("additionalProperties", JsonPrimitive(false))
    }

// ── files.copy ────────────────────────────────────────────────────────────────────────────

// ── files.move ────────────────────────────────────────────────────────────────────────────

// ── files.delete ──────────────────────────────────────────────────────────────────────────
