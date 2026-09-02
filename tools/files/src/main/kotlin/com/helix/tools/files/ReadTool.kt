package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ReadWindow
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.seconds

/**
 * The `read` built-in file tool (roadmap HXA-042 — the FIRST non-`time.now` business tool in the
 * production tool table). Returns a bounded window of a workspace file by offset + byte cap, with
 * encoding-boundary and stable-EOF semantics (the windowing is [com.helix.core.workspace.ReadWindow]).
 *
 * The model references a file ONLY by its model-safe [FileScopePath] reference (`scope:<id>:<rel>`);
 * the real path never appears in arguments or output (doc 10). The output carries the decoded
 * [ReadWindow.text] for UTF-8 content or a [ReadWindow.base64] payload for binary/UTF-16, plus the
 * pagination fields the model uses to read the next chunk (`offset`/`windowLength`/`sizeBytes`/
 * `nextOffset`/`eof`).
 *
 * Contract: L1 base risk, READ_ONLY operation class, idempotent, LOCAL_ANDROID, built-in origin,
 * no required capabilities (workspace reads need no OS permission). The 10 MiB file case is the
 * chunking contract: each call returns at most one 1 MiB window; the model pages with `offset` =
 * the previous `nextOffset` until `eof`.
 */
@Suppress("TooManyFunctions") // one helper per schema primitive; splitting fragments the tool
object ReadTool {
    const val NAME: String = "read"

    const val VERSION: Int = 1

    const val DEFAULT_MAX_BYTES: Long = 1024L * 1024L // one 1 MiB chunk: a 10 MiB file is 10 reads

    /** The registered contract. Input: `path` (model ref), optional `offset`/`maxBytes`. */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "Read a bounded window of a workspace file by model reference, with offset/maxBytes paging.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = ReadWindow.MAX_WINDOW_BYTES * 4,
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
                        stringSchema(maxLength = 512, "Model reference: scope:<scopeId>:<relativePath>"),
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(0))
                            put("default", JsonPrimitive(0))
                            put("description", JsonPrimitive("Byte offset to start the window at"))
                        },
                    )
                    put(
                        "maxBytes",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(ReadWindow.MAX_WINDOW_BYTES))
                            put("default", JsonPrimitive(DEFAULT_MAX_BYTES))
                            put("description", JsonPrimitive("Maximum number of bytes to return"))
                        },
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
                    put("path", stringSchema(maxLength = 512, null))
                    put("offset", integerSchema())
                    put("windowLength", integerSchema())
                    put("sizeBytes", integerSchema())
                    put("encoding", stringSchema(maxLength = 16, null))
                    put("consumedBytes", integerSchema())
                    put("nextOffset", integerSchema())
                    put("eof", booleanSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("offset"),
                        JsonPrimitive("windowLength"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("encoding"),
                        JsonPrimitive("consumedBytes"),
                        JsonPrimitive("nextOffset"),
                        JsonPrimitive("eof"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(true))
        }

    /** The implementation bound to [descriptor]. */
    fun executor(store: WorkspaceArtifactStore): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return runRead(store, call)
            }

            /**
             * Parses, reads and shapes one call; every terminal condition is a stable, sanitized report
             * (the internal exception text is dropped on purpose — it may carry scope ids or path
             * grammar the model must never see).
             */
            @Suppress("SwallowedException") // sanitized messages deliberately drop the internal exception detail
            private fun runRead(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val parsed = parseArgs(call.args) ?: return ToolExecutorResult.Failed("invalid 'read' arguments")
                return try {
                    val window = store.readWindow(parsed.path, parsed.offset, parsed.maxBytes)
                    ToolExecutorResult.Completed(output(window, parsed.path))
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("file not found or not a regular file: ${parsed.path.toModelReference()}")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
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

    /** A parsed `read` argument set, or null when `path` is missing or its reference is invalid. */
    private data class Parsed(
        val path: FileScopePath,
        val offset: Long,
        val maxBytes: Long,
    )

    /**
     * Extracts [Parsed] from the (already schema-validated) arguments. A malformed model reference
     * is reported as absent (null) — the sanitized "invalid arguments" message keeps the internal
     * reference grammar and scope ids away from the model.
     */
    @Suppress("SwallowedException") // a bad model reference is deliberately null, not propagated (sanitized)
    private fun parseArgs(args: JsonObject): Parsed? {
        val ref = args["path"]?.jsonPrimitive?.content
        val path = ref?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        return if (ref == null || path == null) {
            null
        } else {
            Parsed(path, longValue(args["offset"]) ?: 0L, longValue(args["maxBytes"]) ?: DEFAULT_MAX_BYTES)
        }
    }

    /** Reads an optional integer argument (a numeric primitive), or null when absent/not a number. */
    private fun longValue(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.let {
            if (it.isString) it.content.toLongOrNull() else it.longOrNull
        }

    private fun output(
        window: ReadWindow,
        path: FileScopePath,
    ): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path.toModelReference()))
            put("offset", JsonPrimitive(window.offset))
            put("windowLength", JsonPrimitive(window.windowLength.toLong()))
            put("sizeBytes", JsonPrimitive(window.sizeBytes))
            put("encoding", JsonPrimitive(window.encoding.name))
            put("consumedBytes", JsonPrimitive(window.consumedBytes))
            put("nextOffset", JsonPrimitive(window.nextOffset))
            put("eof", JsonPrimitive(window.eof))
            window.text?.let { put("text", JsonPrimitive(it)) }
            window.base64?.let { put("base64", JsonPrimitive(it)) }
        }

    private fun stringSchema(
        maxLength: Int,
        description: String?,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("maxLength", JsonPrimitive(maxLength))
            description?.let { put("description", JsonPrimitive(it)) }
        }

    private fun integerSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("integer")) }

    private fun booleanSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("boolean")) }
}
