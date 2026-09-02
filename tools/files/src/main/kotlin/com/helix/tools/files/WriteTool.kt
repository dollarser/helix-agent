package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.PreconditionHashMismatch
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.WorkspaceQuota
import com.helix.core.workspace.WriteOutcome
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
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The `write` built-in file tool (roadmap HXA-042). Atomically publishes [content] (its UTF-8
 * bytes) to a workspace file referenced by its model-safe [FileScopePath] reference
 * (`scope:<id>:<rel>`), routed through [WorkspaceArtifactStore.writeArtifact] — the HXA-041
 * atomic publish (temp + fsync + atomic replace), the 前置 hash guard, and the workspace quota.
 *
 * Overwrite policy is EXPLICIT and fail-closed: a file that already exists is NOT replaced unless
 * `overwrite` is `true`. With `overwrite` and an `expectedSha256`, the write is additionally
 * guarded by the 前置 hash (optimistic concurrency) — a file changed underneath the model makes the
 * write a [ToolExecutorResult.Failed] with a stable "re-read then retry" message, never a silent
 * clobber. The real path never appears in arguments or output (doc 10): a directory target is
 * refused up front with a stable message, and any I/O failure inside the atomic publish is
 * reported sanitized — the raw exception text (which may carry real paths) never reaches the
 * model or the batch caller.
 *
 * Contract: L2 base risk (a per-call-approval mutation), LOCAL_MUTATION operation class, idempotent
 * (re-writing identical content has no additional effect), LOCAL_ANDROID, built-in origin, no
 * required capabilities (workspace writes need no OS permission). Only the three user-visible
 * regions (`input`/`work`/`output`) are writable — the scope root and `.helix/` internals are not.
 */
@Suppress("TooManyFunctions") // one helper per schema primitive; splitting fragments the tool
object WriteTool {
    const val NAME: String = "write"

    const val VERSION: Int = 1

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** The registered contract. Input: `path`, `content`, optional `overwrite`/`expectedSha256`. */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Atomically write UTF-8 content to a workspace file by model reference; " +
                    "existing files require overwrite=true.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 8 * 1024,
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
                        stringSchema(
                            maxLength = 512,
                            description = "Model reference: scope:<scopeId>:<relativePath> (input/, work/ or output/)",
                        ),
                    )
                    put(
                        "content",
                        stringSchema(maxLength = null, description = "The UTF-8 text content to write"),
                    )
                    put(
                        "overwrite",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("default", JsonPrimitive(false))
                            put("description", JsonPrimitive("Replace the file if it already exists (default false)"))
                        },
                    )
                    put(
                        "expectedSha256",
                        stringSchema(
                            maxLength = 64,
                            description =
                                "When overwriting, the SHA-256 the current file must have (64-hex); " +
                                    "else the write fails",
                        ),
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringSchema(maxLength = 512, description = null))
                    put("sizeBytes", integerSchema())
                    put("sha256", stringSchema(maxLength = 64, description = null))
                    put("encoding", stringSchema(maxLength = 16, description = null))
                    put("mimeType", stringSchema(maxLength = 64, description = null))
                    put("overwritten", booleanSchema())
                    put("usageBytesAfter", integerSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("sha256"),
                        JsonPrimitive("encoding"),
                        JsonPrimitive("mimeType"),
                        JsonPrimitive("overwritten"),
                        JsonPrimitive("usageBytesAfter"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    /** The implementation bound to [descriptor]. */
    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runWrite(store, call)
            }

            /**
             * Parses, guards and publishes one call; every terminal condition is a stable, sanitized
             * report (the internal exception text is dropped on purpose — it may carry scope ids,
             * quota figures or path grammar the model must never see).
             */
            @Suppress("ReturnCount", "SwallowedException") // sanitized failure messages; distinct outcomes
            private fun runWrite(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val parsed = parseArgs(call.args) ?: return ToolExecutorResult.Failed("invalid 'write' arguments")
                return try {
                    val region = WorkspaceLayout.regionOf(parsed.path.relativePath)
                    if (region == null || !WorkspaceLayout.isRegion(region)) {
                        return ToolExecutorResult.Failed(
                            "destination must be inside input/, work/ or output/: ${parsed.path.toModelReference()}",
                        )
                    }
                    // stat (not probe): a directory target reports "absent" to the probe, and letting
                    // it through would make the atomic move fail with a raw exception whose message
                    // carries real paths (doc 10) — refuse it up front with a stable message.
                    val st = store.stat(parsed.path)
                    if (st.isDirectory) {
                        return ToolExecutorResult.Failed(
                            "destination is a directory, not a file: ${parsed.path.toModelReference()}",
                        )
                    }
                    val exists = st.isRegularFile
                    if (exists && !parsed.overwrite) {
                        return ToolExecutorResult.Failed(
                            "file already exists; pass overwrite=true to replace it: ${parsed.path.toModelReference()}",
                        )
                    }
                    val expected = if (exists && parsed.expectedSha256 != null) parsed.expectedSha256 else null
                    val outcome =
                        store.writeArtifact(
                            path = parsed.path,
                            bytes = parsed.content.toByteArray(Charsets.UTF_8),
                            region = region,
                            expectedPreviousSha256 = expected,
                        )
                    ToolExecutorResult.Completed(output(parsed.path, outcome, exists))
                } catch (e: PreconditionHashMismatch) {
                    ToolExecutorResult.Failed(
                        "file changed since you read it (hash mismatch); " +
                            "re-read it and retry with a fresh expectedSha256",
                    )
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the write was not performed")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    // I/O failure inside the atomic publish (disk error, unsupported atomic move):
                    // sanitized — the raw message may carry real paths (doc 10).
                    ToolExecutorResult.Failed("workspace I/O failure; the write was not performed")
                }
            }
        }

    /** Registers both the contract and the implementation in the given registries. */
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        store: WorkspaceArtifactStore,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(store))
    }

    /** A parsed `write` argument set, or null when any required field is missing or malformed. */
    private data class Parsed(
        val path: FileScopePath,
        val content: String,
        val overwrite: Boolean,
        val expectedSha256: String?,
    )

    /**
     * Extracts [Parsed] from the (already schema-validated) arguments. A malformed model reference
     * or a non-hex `expectedSha256` is reported as absent (null) — the sanitized "invalid arguments"
     * message keeps the internal reference grammar and scope ids away from the model.
     */
    @Suppress("SwallowedException") // a bad model reference / hash is deliberately null, not propagated (sanitized)
    private fun parseArgs(args: JsonObject): Parsed? {
        val ref = args["path"]?.jsonPrimitive?.content
        val content = args["content"]?.jsonPrimitive?.content
        val path = ref?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        val expected = args["expectedSha256"]?.jsonPrimitive?.content
        // The optional hash, when present, must be well-formed 64-hex or the whole call is invalid.
        val badHash = expected != null && !SHA256_HEX.matches(expected)
        val missing = ref == null || content == null || path == null
        if (missing || badHash) return null
        val overwrite = boolValue(args["overwrite"]) ?: false
        return Parsed(path, content, overwrite, expected)
    }

    /** Reads an optional boolean argument, or null when absent/not a boolean. */
    private fun boolValue(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.let { p ->
            when {
                p.isString -> p.content.toBooleanStrictOrNull()
                p.content == "true" -> true
                p.content == "false" -> false
                else -> null
            }
        }

    private fun output(
        path: FileScopePath,
        outcome: WriteOutcome,
        overwritten: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path.toModelReference()))
            put("sizeBytes", JsonPrimitive(outcome.record.sizeBytes))
            put("sha256", JsonPrimitive(outcome.record.sha256))
            put("encoding", JsonPrimitive(outcome.probe.encoding.name))
            put("mimeType", JsonPrimitive(outcome.record.mediaType))
            put("overwritten", JsonPrimitive(overwritten))
            put("usageBytesAfter", JsonPrimitive(outcome.usageBytesAfter))
        }

    private fun stringSchema(
        maxLength: Int?,
        description: String?,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            maxLength?.let { put("maxLength", JsonPrimitive(it)) }
            description?.let { put("description", JsonPrimitive(it)) }
        }

    private fun integerSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

    private fun booleanSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("boolean")) }
}
