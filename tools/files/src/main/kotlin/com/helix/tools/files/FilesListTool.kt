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
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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
                    put(
                        "path",
                        filesMetaToolsStr(
                            maxLength = 512,
                            "Model reference of a directory: scope:<scopeId>:<relativePath>",
                        ),
                    )
                    put(
                        "maxEntries",
                        filesMetaToolsIntObject(
                            1,
                            MAX_MAX_ENTRIES,
                            DEFAULT_MAX_ENTRIES,
                            "Maximum number of entries to return",
                        ),
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
                    put("path", filesMetaToolsStr(maxLength = 512, null))
                    put("entries", filesMetaToolsStringArray(maxItems = MAX_MAX_ENTRIES))
                    put("truncated", filesMetaToolsBool())
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
                val path =
                    filesMetaToolsParsePath(call.args)
                        ?: return ToolExecutorResult.Failed("invalid 'files.list' arguments")
                val max =
                    filesMetaToolsIntArg(call.args, "maxEntries")?.coerceIn(1, MAX_MAX_ENTRIES) ?: DEFAULT_MAX_ENTRIES
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
