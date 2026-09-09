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
import com.helix.core.workspace.WorkspaceQuota
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import kotlin.time.Duration.Companion.seconds

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
            inputSchema = filesMutateToolsCopyMoveInputSchema(),
            outputSchema = filesMutateToolsCopyMoveOutputSchema(),
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
                val source = filesMutateToolsRefArg(call.args, "source")
                val destination = filesMutateToolsRefArg(call.args, "destination")
                if (source == null || destination == null) {
                    return ToolExecutorResult.Failed("invalid 'files.copy' arguments")
                }
                val srcRegion = filesMutateToolsUserRegionOf(source)
                if (srcRegion == null) {
                    return ToolExecutorResult.Failed(
                        "source must be inside input/, work/ or output/: ${source.toModelReference()}",
                    )
                }
                val dstRegion = filesMutateToolsUserRegionOf(destination)
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
                    val out =
                        store.copyFile(
                            source,
                            destination,
                            dstRegion,
                            filesMutateToolsBoolArg(call.args, "overwrite"),
                        )
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
