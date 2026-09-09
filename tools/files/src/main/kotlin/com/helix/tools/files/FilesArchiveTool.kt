@file:Suppress("TooManyFunctions") // two archive tools share the private schema/arg/store helpers

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
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import kotlin.time.Duration.Companion.seconds

object FilesArchiveTool {
    const val NAME: String = "files.archive"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Create a restricted .zip or .tar archive of a workspace directory and write it " +
                    "into work/. Only regular files and directories are included; symlinks and " +
                    "device entries are refused.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 60.seconds,
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
                        "source",
                        archiveToolsStrSchema(
                            512,
                            "Model ref of the directory to archive: scope:<id>:<path> (input/, work/ or output/)",
                        ),
                    )
                    put(
                        "destination",
                        archiveToolsStrSchema(
                            512,
                            "Model reference of the archive file to create, inside work/: scope:<scopeId>:work/<name>",
                        ),
                    )
                    put("format", archiveToolsEnumSchema(listOf("zip", "tar"), "Container format (default: zip)"))
                    put(
                        "overwrite",
                        archiveToolsBoolSchema("Replace the archive file if it already exists (default: refuse)"),
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
                    put("entryCount", archiveToolsIntSchema())
                    put("sizeBytes", archiveToolsIntSchema())
                    put("sha256", archiveToolsStrSchema(64, null))
                    put("usageBytesAfter", archiveToolsIntSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("destination"),
                        JsonPrimitive("format"),
                        JsonPrimitive("entryCount"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("sha256"),
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
                return runArchive(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException", "LongMethod") // distinct refusals; sanitized detail
            private fun runArchive(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val source = archiveToolsRefArg(call.args, "source")
                val dest = archiveToolsRefArg(call.args, "destination")
                if (source == null || dest == null) {
                    return ToolExecutorResult.Failed("invalid 'files.archive' arguments")
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
                    if (!srcStat.isDirectory) {
                        return ToolExecutorResult.Failed("source is not a directory: ${source.toModelReference()}")
                    }
                    val destStat = store.stat(dest)
                    if (destStat.isDirectory) {
                        return ToolExecutorResult.Failed(
                            "destination is a directory, not a file: ${dest.toModelReference()}",
                        )
                    }
                    if (destStat.isRegularFile && !archiveToolsBoolArg(call.args, "overwrite")) {
                        return ToolExecutorResult.Failed(
                            "destination already exists; pass overwrite=true to replace it: " +
                                "${dest.toModelReference()}",
                        )
                    }
                    val format = archiveToolsParseFormat(call.args)
                    if (format == null) {
                        return ToolExecutorResult.Failed("invalid 'format' argument (must be 'zip' or 'tar')")
                    }
                    val members = ArrayList<ArchiveMember>()
                    archiveToolsCollectMembers(store, source.scopeId, source.relativePath, members, 0)
                    if (members.isEmpty()) {
                        return ToolExecutorResult.Failed(
                            "source directory is empty; nothing to archive",
                        )
                    }
                    val bytes = ArchiveCodec.create(format, members)
                    if (bytes.size.toLong() > MAX_ARCHIVE_FILE_BYTES) {
                        return ToolExecutorResult.Failed("archive exceeds the maximum size")
                    }
                    archiveToolsEnsureParentDir(store, dest, WorkspaceLayout.WORK)
                    val out = store.writeArtifact(dest, bytes, WorkspaceLayout.WORK)
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("destination", JsonPrimitive(dest.toModelReference()))
                            put("format", JsonPrimitive(format.name.lowercase()))
                            put("entryCount", JsonPrimitive(members.size))
                            put("sizeBytes", JsonPrimitive(bytes.size.toLong()))
                            put("sha256", JsonPrimitive(out.record.sha256))
                            put("usageBytesAfter", JsonPrimitive(out.usageBytesAfter))
                        },
                    )
                } catch (e: ArchivePolicyError) {
                    ToolExecutorResult.Failed(e.detail)
                } catch (e: ArchiveCodecException) {
                    ToolExecutorResult.Failed(archiveToolsCodecMessage(e.reason))
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the archive was not written")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("source not found: ${source.toModelReference()}")
                } catch (e: FileAlreadyExistsException) {
                    ToolExecutorResult.Failed(
                        "cannot create the archive directory for: ${dest.toModelReference()}",
                    )
                } catch (e: IOException) {
                    ToolExecutorResult.Failed("workspace I/O failure; the archive was not written")
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
