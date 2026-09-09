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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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
                        filesMutateToolsStrSchema(
                            512,
                            "Model reference of the file to delete: scope:<scopeId>:<relativePath>",
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
                    put("path", filesMutateToolsStrSchema(512, null))
                    put("trashRef", filesMutateToolsStrSchema(512, null))
                    put("sizeBytes", filesMutateToolsIntSchema())
                    put("usageBytesAfter", filesMutateToolsIntSchema())
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
                val path = filesMutateToolsRefArg(call.args, "path")
                if (path == null) return ToolExecutorResult.Failed("invalid 'files.delete' arguments")
                val region = filesMutateToolsUserRegionOf(path)
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
