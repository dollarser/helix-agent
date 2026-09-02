@file:Suppress("TooManyFunctions") // two archive tools share the private schema/arg/store helpers

package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.WorkspaceQuota
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import kotlin.time.Duration.Companion.seconds

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
private class ArchivePolicyError(
    val detail: String,
) : RuntimeException(detail)

/** Thrown from the per-member extraction callback to abort on a cancel signal. */
private class ArchiveCancelled : RuntimeException()

// Bounds. Entry count, per-file and total size, and the expansion ratio are the roadmap's
// "文件数 / 总大小 / 膨胀比" defenses; they are POLICY (owned here), not format facts.
private const val MAX_ARCHIVE_ENTRIES: Int = 10_000
private const val MAX_ENTRY_BYTES: Long = 16L * 1024 * 1024
private const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024
private const val MAX_EXPANSION_RATIO: Int = 100
private const val MAX_ARCHIVE_FILE_BYTES: Long = 64L * 1024 * 1024
private const val MAX_DEPTH: Int = 64

// ── shared argument / schema helpers (file-private, mirroring the other file tools) ───────────

/** The layout region a path lives in, when it is a user region; null otherwise. */
private fun userRegionOf(path: FileScopePath): String? {
    val region = WorkspaceLayout.regionOf(path.relativePath)
    return if (region != null && WorkspaceLayout.isRegion(region)) region else null
}

/** A model reference argument, or null when absent or malformed (fail-closed, sanitized). */
private fun refArg(
    args: JsonObject,
    key: String,
): FileScopePath? =
    args[key]?.jsonPrimitive?.content?.let {
        runCatching { FileScopePath.fromModelReference(it) }.getOrNull()
    }

/** A boolean argument that accepts both real booleans and the strings "true"/"false". */
private fun boolArg(
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
private fun parseFormat(args: JsonObject): ArchiveFormat? {
    val el = args["format"] ?: return ArchiveFormat.ZIP
    val v = (el as? JsonPrimitive)?.content ?: return null
    return when (v.lowercase()) {
        "zip" -> ArchiveFormat.ZIP
        "tar" -> ArchiveFormat.TAR
        else -> null
    }
}

/** The container format for a file name by extension, or null when it is neither .zip nor .tar. */
private fun archiveFormatFor(fileName: String): ArchiveFormat? =
    when {
        fileName.endsWith(".zip", ignoreCase = true) -> ArchiveFormat.ZIP
        fileName.endsWith(".tar", ignoreCase = true) -> ArchiveFormat.TAR
        else -> null
    }

/** Maps a stable codec reason to a sanitized, model-visible detail (no raw paths). */
private fun codecMessage(reason: String): String =
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

private fun strSchema(
    maxLength: Int?,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        description?.let { put("description", JsonPrimitive(it)) }
    }

private fun boolSchema(description: String?): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("default", JsonPrimitive(false))
        description?.let { put("description", JsonPrimitive(it)) }
    }

private fun intSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

private fun enumSchema(
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
private fun ensureParentDir(
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
private fun ensureDir(
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
private fun collectMembers(
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
                total += collectMembers(store, scopeId, childRel, members, depth + 1)
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

object FilesArchiveTool {
    const val NAME: String = "files.archive"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Create a restricted .zip or .tar archive of a workspace directory and write it " +
                    "into work/. Only regular files and directories are included; symlinks and " +
                    "device entries are refused.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 60.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "source",
                        strSchema(
                            512,
                            "Model ref of the directory to archive: scope:<id>:<path> (input/, work/ or output/)",
                        ),
                    )
                    put(
                        "destination",
                        strSchema(
                            512,
                            "Model reference of the archive file to create, inside work/: scope:<scopeId>:work/<name>",
                        ),
                    )
                    put("format", enumSchema(listOf("zip", "tar"), "Container format (default: zip)"))
                    put("overwrite", boolSchema("Replace the archive file if it already exists (default: refuse)"))
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("source"), JsonPrimitive("destination"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("destination", strSchema(512, null))
                    put("format", strSchema(8, null))
                    put("entryCount", intSchema())
                    put("sizeBytes", intSchema())
                    put("sha256", strSchema(64, null))
                    put("usageBytesAfter", intSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("destination"),
                        JsonPrimitive("format"),
                        JsonPrimitive("entryCount"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("sha256"),
                        JsonPrimitive("usageBytesAfter"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runArchive(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException", "LongMethod") // distinct refusals; sanitized detail
            private fun runArchive(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val source = refArg(call.args, "source")
                val dest = refArg(call.args, "destination")
                if (source == null || dest == null) {
                    return ToolExecutorResult.Failed("invalid 'files.archive' arguments")
                }
                if (source.scopeId != dest.scopeId) {
                    return ToolExecutorResult.Failed("source and destination must be in the same scope")
                }
                if (userRegionOf(source) == null) {
                    return ToolExecutorResult.Failed(
                        "source must be inside input/, work/ or output/: ${source.toModelReference()}",
                    )
                }
                if (WorkspaceLayout.regionOf(dest.relativePath) != WorkspaceLayout.WORK) {
                    return ToolExecutorResult.Failed(
                        "destination must be inside work/: ${dest.toModelReference()}",
                    )
                }
                return try {
                    val srcStat = store.stat(source)
                    if (!srcStat.isDirectory) {
                        return ToolExecutorResult.Failed("source is not a directory: ${source.toModelReference()}")
                    }
                    val destStat = store.stat(dest)
                    if (destStat.isDirectory) {
                        return ToolExecutorResult.Failed(
                            "destination is a directory, not a file: ${dest.toModelReference()}",
                        )
                    }
                    if (destStat.isRegularFile && !boolArg(call.args, "overwrite")) {
                        return ToolExecutorResult.Failed(
                            "destination already exists; pass overwrite=true to replace it: " +
                                "${dest.toModelReference()}",
                        )
                    }
                    val format = parseFormat(call.args)
                    if (format == null) {
                        return ToolExecutorResult.Failed("invalid 'format' argument (must be 'zip' or 'tar')")
                    }
                    val members = ArrayList<ArchiveMember>()
                    collectMembers(store, source.scopeId, source.relativePath, members, 0)
                    if (members.isEmpty()) {
                        return ToolExecutorResult.Failed(
                            "source directory is empty; nothing to archive",
                        )
                    }
                    val bytes = ArchiveCodec.create(format, members)
                    if (bytes.size.toLong() > MAX_ARCHIVE_FILE_BYTES) {
                        return ToolExecutorResult.Failed("archive exceeds the maximum size")
                    }
                    ensureParentDir(store, dest, WorkspaceLayout.WORK)
                    val out = store.writeArtifact(dest, bytes, WorkspaceLayout.WORK)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("destination", JsonPrimitive(dest.toModelReference()))
                            put("format", JsonPrimitive(format.name.lowercase()))
                            put("entryCount", JsonPrimitive(members.size))
                            put("sizeBytes", JsonPrimitive(bytes.size.toLong()))
                            put("sha256", JsonPrimitive(out.record.sha256))
                            put("usageBytesAfter", JsonPrimitive(out.usageBytesAfter))
                        },
                    )
                } catch (e: ArchivePolicyError) {
                    ToolExecutorResult.Failed(e.detail)
                } catch (e: ArchiveCodecException) {
                    ToolExecutorResult.Failed(codecMessage(e.reason))
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the archive was not written")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("source not found: ${source.toModelReference()}")
                } catch (e: FileAlreadyExistsException) {
                    ToolExecutorResult.Failed(
                        "cannot create the archive directory for: ${dest.toModelReference()}",
                    )
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; the archive was not written")
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        store: WorkspaceArtifactStore,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(store))
    }
}

// ── files.extract ───────────────────────────────────────────────────────────────────────────────

object FilesExtractTool {
    const val NAME: String = "files.extract"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Extract a restricted .zip or .tar workspace file into an existing directory " +
                    "inside work/. Only regular-file and directory entries are written; symlink, " +
                    "device and other entries are refused, and entry paths cannot escape the destination.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 60.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "source",
                        strSchema(
                            512,
                            "Model reference of the .zip or .tar file to extract: scope:<scopeId>:<relativePath>",
                        ),
                    )
                    put(
                        "destination",
                        strSchema(
                            512,
                            "Model ref of the existing dir to extract into: scope:<id>:work/<dir>",
                        ),
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("source"), JsonPrimitive("destination"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("destination", strSchema(512, null))
                    put("format", strSchema(8, null))
                    put("files", intSchema())
                    put("directories", intSchema())
                    put("usageBytesAfter", intSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("destination"),
                        JsonPrimitive("format"),
                        JsonPrimitive("files"),
                        JsonPrimitive("directories"),
                        JsonPrimitive("usageBytesAfter"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runExtract(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException", "LongMethod") // distinct refusals; sanitized detail
            private fun runExtract(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val source = refArg(call.args, "source")
                val dest = refArg(call.args, "destination")
                if (source == null || dest == null) {
                    return ToolExecutorResult.Failed("invalid 'files.extract' arguments")
                }
                if (source.scopeId != dest.scopeId) {
                    return ToolExecutorResult.Failed("source and destination must be in the same scope")
                }
                if (userRegionOf(source) == null) {
                    return ToolExecutorResult.Failed(
                        "source must be inside input/, work/ or output/: ${source.toModelReference()}",
                    )
                }
                if (WorkspaceLayout.regionOf(dest.relativePath) != WorkspaceLayout.WORK) {
                    return ToolExecutorResult.Failed(
                        "destination must be inside work/: ${dest.toModelReference()}",
                    )
                }
                return try {
                    val srcStat = store.stat(source)
                    if (!srcStat.isRegularFile) {
                        return ToolExecutorResult.Failed("source is not a file: ${source.toModelReference()}")
                    }
                    val destStat = store.stat(dest)
                    if (!destStat.exists) {
                        return ToolExecutorResult.Failed(
                            "destination directory does not exist: ${dest.toModelReference()}",
                        )
                    }
                    if (!destStat.isDirectory) {
                        return ToolExecutorResult.Failed("destination is not a directory: ${dest.toModelReference()}")
                    }
                    val format = archiveFormatFor(source.name)
                    if (format == null) {
                        return ToolExecutorResult.Failed(
                            "unsupported archive format (use a .zip or .tar file): ${source.toModelReference()}",
                        )
                    }
                    val bytes = store.readAll(source)
                    if (bytes.isEmpty()) {
                        return ToolExecutorResult.Failed("source is not a readable file: ${source.toModelReference()}")
                    }
                    if (bytes.size.toLong() > MAX_ARCHIVE_FILE_BYTES) {
                        return ToolExecutorResult.Failed("archive exceeds the maximum size")
                    }
                    val ensured = HashSet<String>()
                    var files = 0
                    var dirs = 0
                    ArchiveCodec.extract(
                        format,
                        bytes,
                        ArchiveLimits(MAX_ARCHIVE_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MAX_EXPANSION_RATIO),
                    ) { member ->
                        if (call.cancel.isCancelled()) throw ArchiveCancelled()
                        val target = FileScopePath(dest.scopeId, "${dest.relativePath}/${member.name}")
                        when (member) {
                            is ArchiveDir -> {
                                ensureDir(store, target, WorkspaceLayout.WORK, ensured)
                                dirs++
                            }

                            is ArchiveFile -> {
                                ensureDir(store, target.parent, WorkspaceLayout.WORK, ensured)
                                store.writeArtifact(target, member.content, WorkspaceLayout.WORK)
                                files++
                            }
                        }
                    }
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("destination", JsonPrimitive(dest.toModelReference()))
                            put("format", JsonPrimitive(format.name.lowercase()))
                            put("files", JsonPrimitive(files))
                            put("directories", JsonPrimitive(dirs))
                            put("usageBytesAfter", JsonPrimitive(store.usageBytes(dest.scopeId)))
                        },
                    )
                } catch (e: ArchiveCancelled) {
                    ToolExecutorResult.Cancelled
                } catch (e: ArchivePolicyError) {
                    ToolExecutorResult.Failed(e.detail)
                } catch (e: ArchiveCodecException) {
                    ToolExecutorResult.Failed(codecMessage(e.reason))
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; extraction stopped")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: FileAlreadyExistsException) {
                    ToolExecutorResult.Failed("cannot create an extraction directory; extraction stopped")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; extraction stopped")
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        store: WorkspaceArtifactStore,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(store))
    }
}
