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

/** The shared `source`/`destination`/`overwrite` contract of `files.copy` and `files.move`. */
private fun copyMoveInputSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "source",
                    strSchema(512, "Model reference of the file to copy: scope:<scopeId>:<relativePath>"),
                )
                put(
                    "destination",
                    strSchema(512, "Model reference of the target: scope:<scopeId>:<relativePath>"),
                )
                put(
                    "overwrite",
                    boolSchema("Replace the destination when it exists (default: refuse)"),
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
private fun copyMoveOutputSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("source", strSchema(512, null))
                put("destination", strSchema(512, null))
                put("sizeBytes", intSchema())
                put("sha256", strSchema(64, null))
                put("overwritten", boolSchema(null))
                put("usageBytesAfter", intSchema())
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

object FilesCopyTool {
    const val NAME: String = "files.copy"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Copy a workspace file to another location (same or another scope). An existing " +
                    "destination is refused unless overwrite is set; a directory destination is always refused.",
            inputSchema = copyMoveInputSchema(),
            outputSchema = copyMoveOutputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runCopy(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException") // distinct refusals; sanitized failure messages
            private fun runCopy(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val source = refArg(call.args, "source")
                val destination = refArg(call.args, "destination")
                if (source == null || destination == null) {
                    return ToolExecutorResult.Failed("invalid 'files.copy' arguments")
                }
                val srcRegion = userRegionOf(source)
                if (srcRegion == null) {
                    return ToolExecutorResult.Failed(
                        "source must be inside input/, work/ or output/: ${source.toModelReference()}",
                    )
                }
                val dstRegion = userRegionOf(destination)
                if (dstRegion == null) {
                    return ToolExecutorResult.Failed(
                        "destination must be inside input/, work/ or output/: ${destination.toModelReference()}",
                    )
                }
                return try {
                    val st = store.stat(destination)
                    if (st.isDirectory) {
                        return ToolExecutorResult.Failed(
                            "destination is a directory, not a file: ${destination.toModelReference()}",
                        )
                    }
                    val out = store.copyFile(source, destination, dstRegion, boolArg(call.args, "overwrite"))
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("source", JsonPrimitive(source.toModelReference()))
                            put("destination", JsonPrimitive(destination.toModelReference()))
                            put("sizeBytes", JsonPrimitive(out.sizeBytes))
                            put("sha256", JsonPrimitive(out.sha256))
                            put("overwritten", JsonPrimitive(out.overwritten))
                            put("usageBytesAfter", JsonPrimitive(out.usageBytesAfter))
                        },
                    )
                } catch (e: FileAlreadyExistsException) {
                    ToolExecutorResult.Failed("destination already exists; pass overwrite=true to replace it")
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("source not found: ${source.toModelReference()}")
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the copy was not performed")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; the copy was not performed")
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

// ── files.move ────────────────────────────────────────────────────────────────────────────

object FilesMoveTool {
    const val NAME: String = "files.move"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Move a workspace file to another location (same or another scope). An existing " +
                    "destination is refused unless overwrite is set; a directory destination is always refused.",
            inputSchema = copyMoveInputSchema(),
            outputSchema = copyMoveOutputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runMove(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException") // distinct refusals; sanitized failure messages
            private fun runMove(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val source = refArg(call.args, "source")
                val destination = refArg(call.args, "destination")
                if (source == null || destination == null) {
                    return ToolExecutorResult.Failed("invalid 'files.move' arguments")
                }
                val srcRegion = userRegionOf(source)
                if (srcRegion == null) {
                    return ToolExecutorResult.Failed(
                        "source must be inside input/, work/ or output/: ${source.toModelReference()}",
                    )
                }
                val dstRegion = userRegionOf(destination)
                if (dstRegion == null) {
                    return ToolExecutorResult.Failed(
                        "destination must be inside input/, work/ or output/: ${destination.toModelReference()}",
                    )
                }
                return try {
                    val st = store.stat(destination)
                    if (st.isDirectory) {
                        return ToolExecutorResult.Failed(
                            "destination is a directory, not a file: ${destination.toModelReference()}",
                        )
                    }
                    val out = store.moveFile(source, destination, dstRegion, boolArg(call.args, "overwrite"))
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("source", JsonPrimitive(source.toModelReference()))
                            put("destination", JsonPrimitive(destination.toModelReference()))
                            put("sizeBytes", JsonPrimitive(out.sizeBytes))
                            put("sha256", JsonPrimitive(out.sha256))
                            put("overwritten", JsonPrimitive(out.overwritten))
                            put("usageBytesAfter", JsonPrimitive(out.usageBytesAfter))
                        },
                    )
                } catch (e: FileAlreadyExistsException) {
                    ToolExecutorResult.Failed("destination already exists; pass overwrite=true to replace it")
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("source not found: ${source.toModelReference()}")
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the move was not performed")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; the move was not performed")
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

// ── files.delete ──────────────────────────────────────────────────────────────────────────

object FilesDeleteTool {
    const val NAME: String = "files.delete"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Delete a workspace file. The file is never erased: it is moved to the scope's " +
                    "trash, where a separate restore (or a separate purge) can act on it.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
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
                        "path",
                        strSchema(512, "Model reference of the file to delete: scope:<scopeId>:<relativePath>"),
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", strSchema(512, null))
                    put("trashRef", strSchema(512, null))
                    put("sizeBytes", intSchema())
                    put("usageBytesAfter", intSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("trashRef"),
                        JsonPrimitive("sizeBytes"),
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
                return runDelete(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException") // distinct refusals; sanitized failure messages
            private fun runDelete(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val path = refArg(call.args, "path")
                if (path == null) return ToolExecutorResult.Failed("invalid 'files.delete' arguments")
                val region = userRegionOf(path)
                if (region == null) {
                    return ToolExecutorResult.Failed(
                        "path must be inside input/, work/ or output/: ${path.toModelReference()}",
                    )
                }
                return try {
                    val st = store.stat(path)
                    if (!st.exists) return ToolExecutorResult.Failed("file not found: ${path.toModelReference()}")
                    if (!st.isRegularFile) {
                        return ToolExecutorResult.Failed("path is not a file: ${path.toModelReference()}")
                    }
                    val entry = store.moveToTrash(path)
                    val trashRef = FileScopePath(path.scopeId, WorkspaceLayout.TRASH + "/" + entry.trashName)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("path", JsonPrimitive(path.toModelReference()))
                            put("trashRef", JsonPrimitive(trashRef.toModelReference()))
                            put("sizeBytes", JsonPrimitive(entry.sizeBytes))
                            put("usageBytesAfter", JsonPrimitive(store.usageBytes(path.scopeId)))
                        },
                    )
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("file not found: ${path.toModelReference()}")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; the file was not moved to trash")
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
