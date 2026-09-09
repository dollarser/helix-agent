@file:Suppress("TooManyFunctions") // two archive tools share the private schema/arg/store helpers

package com.helix.tools.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The archive half of the `files.*` namespace (roadmap HXA-047): FilesArchiveTool
 * (`files.archive`, create) and FilesExtractTool (`files.extract`, extract).
 *
 * The FORMAT work (zip/tar parse + produce, the Zip Slip / expansion / entry-type defenses, the
 * size and count bounds) lives in the pure, store-independent [ArchiveCodec]. This file is the
 * thin tool layer: it admits scopes and regions, does containment and quota through the
 * [WorkspaceArtifactStore] (which re-checks every write), and maps the codec's stable reason codes
 * to sanitized model-visible detail. Model-safe FileScopePath references only — the real path never
 * appears in arguments, output, or failure text (doc 10).
 *
 * Placement policy (explicit, fail-closed): `files.archive` reads any user region
 * (`input/`/`work/`/`output/`) and writes the archive file into `work/` only. `files.extract`
 * reads a `.zip`/`.tar` file from any user region and writes its members into an EXISTING
 * directory inside `work/` only — extracting into `.helix/` or another scope is impossible because
 * the destination region is fixed to `work/` and the store enforces containment + quota per member.
 * (Plain block comment: it documents the file, not a declaration.)
 */

/** Tool-layer policy failure (distinct from the codec's [ArchiveCodecException]). */
internal class ArchivePolicyError(
    val detail: String,
) : RuntimeException(detail)

/** Thrown from the per-member extraction callback to abort on a cancel signal. */
internal class ArchiveCancelled : RuntimeException()

// Bounds. Entry count, per-file and total size, and the expansion ratio are the roadmap's
// "文件数 / 总大小 / 膨胀比" defenses; they are POLICY (owned here), not format facts.
internal const val MAX_ARCHIVE_ENTRIES: Int = 10_000
internal const val MAX_ENTRY_BYTES: Long = 16L * 1024 * 1024
internal const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024
internal const val MAX_EXPANSION_RATIO: Int = 100
internal const val MAX_ARCHIVE_FILE_BYTES: Long = 64L * 1024 * 1024
internal const val MAX_DEPTH: Int = 64

// ── shared argument / schema helpers (file-private, mirroring the other file tools) ───────────

/** The layout region a path lives in, when it is a user region; null otherwise. */
internal fun archiveToolsUserRegionOf(path: FileScopePath): String? {
    val region = WorkspaceLayout.regionOf(path.relativePath)
    return if (region != null && WorkspaceLayout.isRegion(region)) region else null
}

/** A model reference argument, or null when absent or malformed (fail-closed, sanitized). */
internal fun archiveToolsRefArg(
    args: JsonObject,
    key: String,
): FileScopePath? =
    args[key]?.jsonPrimitive?.content?.let {
        runCatching { FileScopePath.fromModelReference(it) }.getOrNull()
    }

/** A boolean argument that accepts both real booleans and the strings "true"/"false". */
internal fun archiveToolsBoolArg(
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

/** The `format` argument: absent → zip (default); present but not zip/tar → null (invalid). */
@Suppress("ReturnCount") // one return per distinct case: default / malformed / parse
internal fun archiveToolsParseFormat(args: JsonObject): ArchiveFormat? {
    val el = args["format"] ?: return ArchiveFormat.ZIP
    val v = (el as? JsonPrimitive)?.content ?: return null
    return when (v.lowercase()) {
        "zip" -> ArchiveFormat.ZIP
        "tar" -> ArchiveFormat.TAR
        else -> null
    }
}

/** The container format for a file name by extension, or null when it is neither .zip nor .tar. */
internal fun archiveToolsArchiveFormatFor(fileName: String): ArchiveFormat? =
    when {
        fileName.endsWith(".zip", ignoreCase = true) -> ArchiveFormat.ZIP
        fileName.endsWith(".tar", ignoreCase = true) -> ArchiveFormat.TAR
        else -> null
    }

/** Maps a stable codec reason to a sanitized, model-visible detail (no raw paths). */
internal fun archiveToolsCodecMessage(reason: String): String =
    when (reason) {
        "TOO_MANY_ENTRIES" -> "archive exceeds the entry limit ($MAX_ARCHIVE_ENTRIES)"

        "ENTRY_TOO_LARGE" -> "an archive entry exceeds the per-file size limit"

        "TOTAL_TOO_LARGE" -> "archive exceeds the total size limit"

        "EXPANSION_EXCEEDED" -> "archive expansion exceeds the safe ratio limit"

        "UNSUPPORTED_TAR" -> "unsupported tar format"

        "UNSUPPORTED_TAR_PAX" -> "archive uses an unsupported PAX tar extension"

        "UNSUPPORTED_TAR_ENTRY" -> "archive contains an unsupported entry type (e.g. a symlink or device)"

        "NAME_TOO_LONG_FOR_TAR" -> "an archive entry name is too long"

        "TRUNCATED" -> "archive is truncated or corrupt"

        "BAD_TAR_CHECKSUM" -> "archive is corrupt (checksum mismatch)"

        "BAD_TAR_OCTAL" -> "archive is corrupt (malformed field)"

        "BAD_NAME_EMPTY",
        "BAD_NAME_ABSOLUTE",
        "BAD_NAME_EMPTY_SEGMENT",
        "BAD_NAME_DOT",
        "BAD_NAME_BACKSLASH",
        "BAD_NAME_CONTROL",
        -> "archive contains a disallowed entry name (possible path traversal)"

        else -> "archive could not be parsed"
    }

internal fun archiveToolsStrSchema(
    maxLength: Int?,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        description?.let { put("description", JsonPrimitive(it)) }
    }

internal fun archiveToolsBoolSchema(description: String?): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("default", JsonPrimitive(false))
        description?.let { put("description", JsonPrimitive(it)) }
    }

internal fun archiveToolsIntSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

internal fun archiveToolsEnumSchema(
    values: List<String>,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        description?.let { put("description", JsonPrimitive(it)) }
    }

/**
 * Ensures [file]'s immediate parent directory exists (creating missing ancestors via the store's
 * containment-checked mkdir). `writeAtomic` requires the parent to already be a directory, so a
 * nested archive destination must be staged this way.
 */
internal fun archiveToolsEnsureParentDir(
    store: WorkspaceArtifactStore,
    file: FileScopePath,
    region: String,
) {
    val parent = file.parent
    if (parent.isRoot) return
    val st = store.stat(parent)
    when {
        !st.exists -> store.mkdir(parent, region)

        !st.isDirectory -> throw ArchivePolicyError(
            "the destination's parent is not a directory: ${parent.toModelReference()}",
        )
    }
}

/**
 * Ensures the directory [dir] (and any missing ancestors) exists, memoized in [ensured] so a
 * shared parent is only created once across a bulk extract.
 */
internal fun archiveToolsEnsureDir(
    store: WorkspaceArtifactStore,
    dir: FileScopePath,
    region: String,
    ensured: MutableSet<String>,
) {
    if (dir.isRoot) return
    if (ensured.contains(dir.relativePath)) return
    val st = store.stat(dir)
    when {
        !st.exists -> store.mkdir(dir, region)

        !st.isDirectory -> throw ArchivePolicyError(
            "a path component is not a directory: ${dir.toModelReference()}",
        )
    }
    ensured.add(dir.relativePath)
}

/**
 * Recursively collects the regular files and directories under [rel] into [members], enforcing
 * the entry-count, per-file, and total-size bounds. Returns the cumulative byte count of the file
 * members visited. Fails closed ([ArchivePolicyError]) on an unsupported entry (e.g. a symlink),
 * an over-limit file, or an over-deep path.
 */
@Suppress("ThrowsCount", "SwallowedException") // fail-closed bounds; the IAE is a sanitized path refusal
internal fun archiveToolsCollectMembers(
    store: WorkspaceArtifactStore,
    scopeId: String,
    rel: String,
    members: MutableList<ArchiveMember>,
    depth: Int,
): Long {
    if (depth > MAX_DEPTH) throw ArchivePolicyError("archive source is too deeply nested")
    val listing = store.listDir(FileScopePath(scopeId, rel), MAX_ARCHIVE_ENTRIES)
    if (listing.truncated) {
        throw ArchivePolicyError("a source directory has more than $MAX_ARCHIVE_ENTRIES entries")
    }
    var total = 0L
    for (name in listing.entries) {
        val childRel = "$rel/$name"
        val child: FileScopePath =
            try {
                FileScopePath(scopeId, childRel)
            } catch (e: IllegalArgumentException) {
                throw ArchivePolicyError("archive source is too deeply nested")
            }
        val st = store.stat(child)
        when {
            st.isDirectory -> {
                if (members.size >= MAX_ARCHIVE_ENTRIES) {
                    throw ArchivePolicyError("archive exceeds the entry limit ($MAX_ARCHIVE_ENTRIES)")
                }
                members.add(ArchiveDir(childRel))
                total += archiveToolsCollectMembers(store, scopeId, childRel, members, depth + 1)
            }

            st.isRegularFile -> {
                if (members.size >= MAX_ARCHIVE_ENTRIES) {
                    throw ArchivePolicyError("archive exceeds the entry limit ($MAX_ARCHIVE_ENTRIES)")
                }
                if (st.sizeBytes > MAX_ENTRY_BYTES) {
                    throw ArchivePolicyError("a source file exceeds the per-file size limit")
                }
                val content = store.readAll(child)
                if (content.size > MAX_ENTRY_BYTES) {
                    throw ArchivePolicyError("a source file exceeds the per-file size limit")
                }
                members.add(ArchiveFile(childRel, content))
                total += content.size
            }

            else -> {
                throw ArchivePolicyError(
                    "archive source contains an unsupported entry (e.g. a symlink)",
                )
            }
        }
        if (total > MAX_TOTAL_BYTES) throw ArchivePolicyError("archive exceeds the total size limit")
    }
    return total
}

// ── files.archive ───────────────────────────────────────────────────────────────────────────────

// ── files.extract ───────────────────────────────────────────────────────────────────────────────
