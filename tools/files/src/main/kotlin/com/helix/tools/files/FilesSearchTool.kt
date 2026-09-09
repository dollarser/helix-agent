package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
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
import kotlin.time.Duration.Companion.seconds

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
                    put("path", filesMetaToolsStr(maxLength = 512, "Model reference of the directory to search"))
                    put(
                        "needle",
                        filesMetaToolsStr(maxLength = 256, "Substring to match against names (case-insensitive)"),
                    )
                    put(
                        "maxResults",
                        filesMetaToolsIntObject(1, MAX_MAX_RESULTS, DEFAULT_MAX_RESULTS, "Maximum matches to return"),
                    )
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
                    put("path", filesMetaToolsStr(maxLength = 512, null))
                    put("matches", filesMetaToolsStringArray(maxItems = MAX_MAX_RESULTS))
                    put("truncated", filesMetaToolsBool())
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
                val path =
                    filesMetaToolsParsePath(call.args)
                        ?: return ToolExecutorResult.Failed("invalid 'files.search' arguments")
                val needle = call.args["needle"]?.jsonPrimitive?.content
                if (needle.isNullOrBlank()) return ToolExecutorResult.Failed("invalid 'files.search' arguments")
                val max =
                    filesMetaToolsIntArg(call.args, "maxResults")?.coerceIn(1, MAX_MAX_RESULTS) ?: DEFAULT_MAX_RESULTS
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
