package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDiscoveryHints
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

internal object SearchToolContract {
    fun descriptor(content: Boolean): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(if (content) "grep" else "glob"),
            version = ToolVersion(1),
            description =
                (
                    if (content) {
                        "Search file contents for literal text (not regex). Use this to narrow many candidate files " +
                            "before read; returns paths, 1-based line numbers and bounded matching previews. " +
                            "Only UTF-8 files up to 256 KiB are searched; skipped files are counted. "
                    } else {
                        "Find files recursively by relative filename/path patterns, without reading their contents. " +
                            "Use grep when you know text inside the file, or read when you already know its path. "
                    }
                ) + "Hidden files are included; symbolic links are not followed. " +
                    "Follow nextOffset when present. At scan limits, narrow path/pattern instead. " +
                    "Check complete and skipped counts: incomplete results do not prove absence. " +
                    "Pages rescan the directory and are not an atomic snapshot. Read before editing.",
            inputSchema = inputSchema(content),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 30.seconds,
            maxOutputBytes = 512 * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
            discovery = ToolDiscoveryHints(category = "files"),
        )

    private fun pathPatternSchema() =
        buildJsonObject {
            filesMetaToolsStr(
                256,
                "Relative glob: *, ?, whole ** segments; other characters literal. " +
                    "Default **/*; use **/*word* for a filename substring.",
            ).forEach { (key, value) -> put(key, value) }
            put("default", JsonPrimitive("**/*"))
            put("minLength", JsonPrimitive(1))
        }

    private fun inputSchema(content: Boolean) =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", filesMetaToolsStr(512, "Directory reference: scope:<scopeId>:<relativePath>."))
                    put("pattern", pathPatternSchema())
                    if (content) {
                        put(
                            "query",
                            buildJsonObject {
                                filesMetaToolsStr(
                                    256,
                                    "Literal text within a single line; not a regular expression.",
                                ).forEach { (key, value) -> put(key, value) }
                                put("minLength", JsonPrimitive(1))
                                put("pattern", JsonPrimitive("^[^\\r\\n]+$(?![\\r\\n])"))
                            },
                        )
                    }
                    put(
                        "ignoreCase",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("default", JsonPrimitive(false))
                            put("description", JsonPrimitive("Ignore case in path patterns and content query."))
                        },
                    )
                    put(
                        "exclude",
                        buildJsonObject {
                            filesMetaToolsStringArray(8).forEach { (key, value) -> put(key, value) }
                            put(
                                "description",
                                JsonPrimitive("Relative globs to skip; matching directories prune subtrees."),
                            )
                        },
                    )
                    put(
                        "offset",
                        filesMetaToolsIntObject(
                            0,
                            20_000,
                            0,
                            "Omit initially; then copy nextOffset. Counts matches, not bytes; each page rescans.",
                        ),
                    )
                    put("maxResults", filesMetaToolsIntObject(1, 100, 40, "Maximum matches per page"))
                },
            )
            val required = if (content) listOf("path", "query") else listOf("path")
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema() =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "required",
                JsonArray(
                    listOf(
                        "path",
                        "matches",
                        "complete",
                        "truncated",
                        "unreadableEntries",
                        "skippedContentFiles",
                    ).map(::JsonPrimitive),
                ),
            )
            put(
                "properties",
                buildJsonObject {
                    put("path", filesMetaToolsStr(512, null))
                    put("complete", filesMetaToolsBool())
                    put("truncated", filesMetaToolsBool())
                    listOf(
                        "offset",
                        "nextOffset",
                        "scannedEntries",
                        "unreadableEntries",
                        "skippedContentFiles",
                    ).forEach {
                        put(it, filesMetaToolsInt())
                    }
                    put("pagination", filesMetaToolsStr(256, null))
                    put(
                        "matches",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("maxItems", JsonPrimitive(100))
                            put(
                                "items",
                                matchSchema(),
                            )
                        },
                    )
                },
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun matchSchema() =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", filesMetaToolsStr(512, null))
                    put("line", filesMetaToolsInt())
                    put("text", filesMetaToolsStr(240, "Partial line preview"))
                    put("sha256", filesMetaToolsStr(64, null))
                    put("versionKnown", filesMetaToolsBool())
                },
            )
            put("required", JsonArray(listOf("path", "versionKnown").map(::JsonPrimitive)))
            put("additionalProperties", JsonPrimitive(false))
        }

    @Suppress("ReturnCount", "SwallowedException") // Distinct sanitized failure boundaries; no raw provider errors.
    fun executor(
        store: WorkspaceArtifactStore,
        content: Boolean,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return try {
                    ToolExecutorResult.Completed(WorkspaceSearch(store, call, content).run())
                } catch (_: SearchDeadlineExceeded) {
                    ToolExecutorResult.TimedOut
                } catch (_: SearchCancelled) {
                    ToolExecutorResult.Cancelled
                } catch (_: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("Search scope is no longer available; select an authorized directory")
                } catch (_: SymlinkInPath) {
                    ToolExecutorResult.Failed("Search root contains a disallowed symbolic link")
                } catch (_: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("Search root leaves its authorized scope")
                } catch (_: IllegalArgumentException) {
                    ToolExecutorResult.Failed(
                        "Invalid search: path must name an available directory. " +
                            "Pattern uses *, ?, or whole ** segments; " +
                            "grep query must be nonempty literal text on one line. Correct arguments before retrying.",
                        sideEffectFree = true,
                    )
                } catch (_: IOException) {
                    ToolExecutorResult.Failed("Search could not finish; no complete result is available")
                }
            }
        }
}
