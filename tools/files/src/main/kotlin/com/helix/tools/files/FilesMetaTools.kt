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
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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
object FilesStatTool {
    const val NAME: String = "files.stat"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "Stat a workspace path by model reference: existence, size, kind, symlink flag.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
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
                    put("path", str(maxLength = 512, "Model reference: scope:<scopeId>:<relativePath>"))
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
                    put("path", str(maxLength = 512, null))
                    put("exists", bool())
                    put("sizeBytes", int())
                    put("isDirectory", bool())
                    put("isRegularFile", bool())
                    put("isSymlink", bool())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("exists"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("isDirectory"),
                        JsonPrimitive("isRegularFile"),
                        JsonPrimitive("isSymlink"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "SwallowedException") // sanitized failure; outcomes are distinct
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val path = parsePath(call.args) ?: return ToolExecutorResult.Failed("invalid 'files.stat' arguments")
                return try {
                    val s = store.stat(path)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("path", JsonPrimitive(path.toModelReference()))
                            put("exists", JsonPrimitive(s.exists))
                            put("sizeBytes", JsonPrimitive(s.sizeBytes))
                            put("isDirectory", JsonPrimitive(s.isDirectory))
                            put("isRegularFile", JsonPrimitive(s.isRegularFile))
                            put("isSymlink", JsonPrimitive(s.isSymlink))
                        },
                    )
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    // A raw NIO IOException message may carry the absolute real path — a sanitized
                    // failure keeps it away from the model (doc 10).
                    ToolExecutorResult.Failed("stat failed: ${path.toModelReference()}")
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

// ── files.list ────────────────────────────────────────────────────────────────────────────

object FilesListTool {
    const val NAME: String = "files.list"

    const val VERSION: Int = 1

    const val DEFAULT_MAX_ENTRIES: Int = 200

    const val MAX_MAX_ENTRIES: Int = 1000

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "List the immediate children of a workspace directory by model reference (bounded page).",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 16 * 1024,
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
                    put("path", str(maxLength = 512, "Model reference of a directory: scope:<scopeId>:<relativePath>"))
                    put(
                        "maxEntries",
                        intObject(1, MAX_MAX_ENTRIES, DEFAULT_MAX_ENTRIES, "Maximum number of entries to return"),
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
                    put("path", str(maxLength = 512, null))
                    put("entries", stringArray(maxItems = MAX_MAX_ENTRIES))
                    put("truncated", bool())
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("entries"), JsonPrimitive("truncated"))),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "SwallowedException") // sanitized failure; outcomes are distinct
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val path = parsePath(call.args) ?: return ToolExecutorResult.Failed("invalid 'files.list' arguments")
                val max = intArg(call.args, "maxEntries")?.coerceIn(1, MAX_MAX_ENTRIES) ?: DEFAULT_MAX_ENTRIES
                return try {
                    val r = store.listDir(path, max)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("path", JsonPrimitive(path.toModelReference()))
                            put("entries", JsonArray(r.entries.map { JsonPrimitive(it) }))
                            put("truncated", JsonPrimitive(r.truncated))
                        },
                    )
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("not a directory: ${path.toModelReference()}")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("list failed: ${path.toModelReference()}")
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

// ── files.search ──────────────────────────────────────────────────────────────────────────

object FilesSearchTool {
    const val NAME: String = "files.search"

    const val VERSION: Int = 1

    const val DEFAULT_MAX_RESULTS: Int = 100

    const val MAX_MAX_RESULTS: Int = 512

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "Search files under a workspace directory by name substring (case-insensitive, bounded).",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 32 * 1024,
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
                    put("path", str(maxLength = 512, "Model reference of the directory to search"))
                    put("needle", str(maxLength = 256, "Substring to match against names (case-insensitive)"))
                    put("maxResults", intObject(1, MAX_MAX_RESULTS, DEFAULT_MAX_RESULTS, "Maximum matches to return"))
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("needle"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", str(maxLength = 512, null))
                    put("matches", stringArray(maxItems = MAX_MAX_RESULTS))
                    put("truncated", bool())
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("matches"), JsonPrimitive("truncated"))),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "SwallowedException") // sanitized failure; outcomes are distinct
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val path = parsePath(call.args) ?: return ToolExecutorResult.Failed("invalid 'files.search' arguments")
                val needle = call.args["needle"]?.jsonPrimitive?.content
                if (needle.isNullOrBlank()) return ToolExecutorResult.Failed("invalid 'files.search' arguments")
                val max = intArg(call.args, "maxResults")?.coerceIn(1, MAX_MAX_RESULTS) ?: DEFAULT_MAX_RESULTS
                return try {
                    val r = store.search(path, needle, max, WorkspaceArtifactStore.MAX_SEARCH_SCAN)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("path", JsonPrimitive(path.toModelReference()))
                            put("matches", JsonArray(r.matches.map { JsonPrimitive(it.toModelReference()) }))
                            put("truncated", JsonPrimitive(r.truncated))
                        },
                    )
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("not a directory: ${path.toModelReference()}")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("search failed: ${path.toModelReference()}")
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

// ── files.mkdir ───────────────────────────────────────────────────────────────────────────

object FilesMkdirTool {
    const val NAME: String = "files.mkdir"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Create a directory (and missing parents) in a workspace region by model reference; " +
                    "fails if it exists.",
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
                        str(maxLength = 512, "Model reference of the directory to create (input/, work/ or output/)"),
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
                    put("path", str(maxLength = 512, null))
                    put("created", bool())
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("created"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "SwallowedException") // sanitized failure; outcomes are distinct
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val path = parsePath(call.args) ?: return ToolExecutorResult.Failed("invalid 'files.mkdir' arguments")
                val ref = path.toModelReference()
                return try {
                    val region = WorkspaceLayout.regionOf(path.relativePath)
                    if (region == null || !WorkspaceLayout.isRegion(region)) {
                        return ToolExecutorResult.Failed("destination must be inside input/, work/ or output/: $ref")
                    }
                    store.mkdir(path, region)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("path", JsonPrimitive(ref))
                            put("created", JsonPrimitive(true))
                        },
                    )
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    ToolExecutorResult.Failed("already exists: $ref")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("directory creation failed: $ref")
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

// ── shared, package-private helpers (kept tiny and side-effect free) ──────────────────────

private fun parsePath(args: JsonObject): FileScopePath? {
    val ref = args["path"]?.jsonPrimitive?.content ?: return null
    return runCatching { FileScopePath.fromModelReference(ref) }.getOrNull()
}

private fun intArg(
    args: JsonObject,
    key: String,
): Int? = (args[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun str(
    maxLength: Int,
    description: String?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("maxLength", JsonPrimitive(maxLength))
        description?.let { put("description", JsonPrimitive(it)) }
    }

private fun intObject(
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

private fun bool(): JsonObject = buildJsonObject { put("type", JsonPrimitive("boolean")) }

private fun int(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

private fun stringArray(maxItems: Int): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", JsonObject(mapOf("type" to JsonPrimitive("string"))))
        put("maxItems", JsonPrimitive(maxItems))
    }
