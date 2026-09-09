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
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import kotlin.time.Duration.Companion.seconds

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
                        archiveToolsStrSchema(
                            512,
                            "Model reference of the .zip or .tar file to extract: scope:<scopeId>:<relativePath>",
                        ),
                    )
                    put(
                        "destination",
                        archiveToolsStrSchema(
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
                    put("destination", archiveToolsStrSchema(512, null))
                    put("format", archiveToolsStrSchema(8, null))
                    put("files", archiveToolsIntSchema())
                    put("directories", archiveToolsIntSchema())
                    put("usageBytesAfter", archiveToolsIntSchema())
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
                val source = archiveToolsRefArg(call.args, "source")
                val dest = archiveToolsRefArg(call.args, "destination")
                if (source == null || dest == null) {
                    return ToolExecutorResult.Failed("invalid 'files.extract' arguments")
                }
                if (source.scopeId != dest.scopeId) {
                    return ToolExecutorResult.Failed("source and destination must be in the same scope")
                }
                if (archiveToolsUserRegionOf(source) == null) {
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
                    val format = archiveToolsArchiveFormatFor(source.name)
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
                                archiveToolsEnsureDir(store, target, WorkspaceLayout.WORK, ensured)
                                dirs++
                            }

                            is ArchiveFile -> {
                                archiveToolsEnsureDir(store, target.parent, WorkspaceLayout.WORK, ensured)
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
                    ToolExecutorResult.Failed(archiveToolsCodecMessage(e.reason))
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
