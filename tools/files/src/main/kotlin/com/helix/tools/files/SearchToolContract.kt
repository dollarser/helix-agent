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
                        "Find literal text in UTF-8 lines (grep -F; no regex). " +
                            "Returns 1-based lines, " +
                            "bounded previews and SHA-256 for read-back verification. " +
                            "Binary/UTF-16 and files >256 KiB are skipped. "
                    } else {
                        "Find workspace files by relative path glob. " +
                            "For name substring search: **/*word*, ignoreCase=true. " +
                            "Returns anchored paths, without content versions. "
                    }
                ) + "Glob: *, ?, whole ** segments; other characters literal. Hidden files included. " +
                    "exclude prunes matching directories. Symlinks are not followed. " +
                    "At most 20000 entries / 8 MiB content per call. Results are not an atomic snapshot. " +
                    "offset re-scans. Use nextOffset to page matches; narrow the root at scan limits. " +
                    "Check complete/truncated and skipped counts. Empty partial results do not prove absence. " +
                    "Use read before edits. A stale location never authorizes a write.",
            inputSchema = inputSchema(content),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 30.seconds,
            maxOutputBytes = 512 * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema(content: Boolean) =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", filesMetaToolsStr(512, "Search root model reference"))
                    put("pattern", filesMetaToolsStr(256, "Relative path glob; default **/*"))
                    if (content) put("query", filesMetaToolsStr(256, "Nonempty literal text on one line"))
                    put("ignoreCase", filesMetaToolsBool())
                    put("exclude", filesMetaToolsStringArray(8))
                    put("offset", filesMetaToolsIntObject(0, 20_000, 0, "Number of matches to skip by re-scanning"))
                    put("maxResults", filesMetaToolsIntObject(1, 100, 40, "Maximum matches per page"))
                },
            )
            val required = if (content) listOf("path", "query") else listOf("path", "pattern")
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
                    ToolExecutorResult.Failed("Invalid search arguments or unavailable directory")
                } catch (_: IOException) {
                    ToolExecutorResult.Failed("Search could not finish; no complete result is available")
                }
            }
        }
}
