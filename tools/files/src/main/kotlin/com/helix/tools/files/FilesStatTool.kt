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
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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
                    put("path", filesMetaToolsStr(maxLength = 512, "Model reference: scope:<scopeId>:<relativePath>"))
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
                    put("exists", filesMetaToolsBool())
                    put("sizeBytes", filesMetaToolsInt())
                    put("isDirectory", filesMetaToolsBool())
                    put("isRegularFile", filesMetaToolsBool())
                    put("isSymlink", filesMetaToolsBool())
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
                val path =
                    filesMetaToolsParsePath(call.args)
                        ?: return ToolExecutorResult.Failed("invalid 'files.stat' arguments")
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
