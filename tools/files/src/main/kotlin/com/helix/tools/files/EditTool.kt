package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.ContentProbe
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
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.time.Duration.Companion.seconds

/**
 * The `edit` built-in file tool (roadmap HXA-042): a surgical, content-addressed edit of a
 * workspace text file. Unlike [WriteTool] (whole-file publish) it replaces a precise
 * [oldText] span with [newText], and — critically — only proceeds when the file's current
 * SHA-256 matches [expectedSha256]. That is the optimistic-concurrency guard: the model reads a
 * file (via `read`/`write`), the caller hands back the hash of exactly the bytes it saw, and any
 * intervening change makes this edit a stable "re-read then retry" failure, never a clobber.
 *
 * Uniqueness is fail-closed: [oldText] must occur EXACTLY [replaceAll] times — a single edit
 * needs exactly one match, a replace-all needs at least one. A different count is a failure, not
 * a guess, so an ambiguous target is never edited.
 *
 * The file must be present UTF-8 text over its WHOLE content: the 8 KiB probe is a fast
 * pre-filter only, and the full content is strictly decoded (any malformed byte or NUL refuses)
 * before mutation, so a bad byte past the probe window is never republished as U+FFFD. The real
 * path never appears in arguments or output (doc 10).
 *
 * Contract: L2 base risk (a per-call-approval mutation), LOCAL_MUTATION operation class, idempotent
 * (a matching replace has a single defined effect), LOCAL_ANDROID, built-in origin, no required
 * capabilities. Only the three user-visible regions (`input`/`work`/`output`) are editable.
 */
@Suppress("TooManyFunctions") // one helper per schema primitive; splitting fragments the tool
object EditTool {
    const val NAME: String = "edit"

    const val VERSION: Int = 1

    /** The input content cap; an edit payload never needs more than one 1 MiB window. */
    const val MAX_CONTENT_CHARS: Int = 1024 * 1024

    /**
     * The hard cap on editable file size: an edit decodes the WHOLE file into memory (plus the
     * replaced copy), so an unbounded file would OOM the process — the 1 GiB quota is not an
     * in-memory bound. Larger files are refused with a stable message, not decoded.
     */
    const val MAX_EDITABLE_BYTES: Long = 50L * 1024L * 1024L

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** Probe encodings acceptable for editing (EMPTY passes: the match check fails closed). */
    private val TEXT_ENCODINGS = setOf(ContentProbe.Encoding.UTF8, ContentProbe.Encoding.EMPTY)

    /** The registered contract. Input: `path`, `oldText`, `newText`, `expectedSha256`, `replaceAll`. */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Replace an exact text span in a workspace file; requires the file's current SHA-256 and an " +
                    "unambiguous match.",
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
                            "Model reference: scope:<scopeId>:<relativePath> (input/, work/ or output/)",
                        ),
                    )
                    put(
                        "oldText",
                        stringSchema(
                            maxLength = MAX_CONTENT_CHARS,
                            "The exact existing text to replace (must occur exactly once unless replaceAll)",
                        ),
                    )
                    put(
                        "newText",
                        stringSchema(maxLength = MAX_CONTENT_CHARS, "The replacement text"),
                    )
                    put(
                        "expectedSha256",
                        stringSchema(
                            maxLength = 64,
                            "The SHA-256 (64-hex) of the file as you last read it; the edit is refused otherwise",
                        ),
                    )
                    put(
                        "replaceAll",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("default", JsonPrimitive(false))
                            put(
                                "description",
                                JsonPrimitive("Replace every occurrence (default: exactly one must match)"),
                            )
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("oldText"),
                        JsonPrimitive("newText"),
                        JsonPrimitive("expectedSha256"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("path", stringSchema(maxLength = 512, null))
                    put("replacements", integerSchema())
                    put("sizeBytes", integerSchema())
                    put("sha256", stringSchema(maxLength = 64, null))
                    put("usageBytesAfter", integerSchema())
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("path"),
                        JsonPrimitive("replacements"),
                        JsonPrimitive("sizeBytes"),
                        JsonPrimitive("sha256"),
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
                return runEdit(store, call)
            }

            @Suppress("ReturnCount", "SwallowedException") // sanitized failure messages; distinct outcomes
            private fun runEdit(
                store: WorkspaceArtifactStore,
                call: ExecutableToolCall,
            ): ToolExecutorResult {
                val parsed = parseArgs(call.args) ?: return ToolExecutorResult.Failed("invalid 'edit' arguments")
                val ref = parsed.path.toModelReference()
                return try {
                    val region = WorkspaceLayout.regionOf(parsed.path.relativePath)
                    if (region == null || !WorkspaceLayout.isRegion(region)) {
                        return ToolExecutorResult.Failed("destination must be inside input/, work/ or output/: $ref")
                    }
                    val probe = store.probe(parsed.path)
                    probeRefusal(probe, ref)?.let { return it }
                    val loaded = loadCurrentContent(store, parsed.path, ref)
                    val current =
                        when (loaded) {
                            is CurrentContent.Refusal -> return loaded.result
                            is CurrentContent.Ready -> loaded.text
                        }
                    val count = countNonOverlapping(current, parsed.oldText)
                    if (parsed.replaceAll) {
                        if (count == 0) return ToolExecutorResult.Failed("oldText not found: $ref")
                    } else if (count != 1) {
                        return ToolExecutorResult.Failed(
                            "oldText must occur exactly once (found $count); " +
                                "pass replaceAll or a more specific span: $ref",
                        )
                    }
                    val updated =
                        if (parsed.replaceAll) {
                            current.replace(parsed.oldText, parsed.newText)
                        } else {
                            current.replaceFirst(parsed.oldText, parsed.newText)
                        }
                    val outcome =
                        store.writeArtifact(
                            path = parsed.path,
                            bytes = updated.toByteArray(Charsets.UTF_8),
                            region = region,
                            expectedPreviousSha256 = parsed.expectedSha256,
                        )
                    ToolExecutorResult.Completed(output(parsed.path, count, outcome))
                } catch (e: PreconditionHashMismatch) {
                    ToolExecutorResult.Failed(
                        "file changed since you read it (hash mismatch); " +
                            "re-read it and retry with a fresh expectedSha256",
                    )
                } catch (e: FileNotFoundException) {
                    ToolExecutorResult.Failed("file not found: $ref")
                } catch (e: WorkspaceQuota.QuotaExceeded) {
                    ToolExecutorResult.Failed("workspace quota exceeded; the edit was not performed")
                } catch (e: SymlinkInPath) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: SymlinkEscapesRoot) {
                    ToolExecutorResult.Failed("path rejected: ${e.message}")
                } catch (e: ScopeNotAvailable) {
                    ToolExecutorResult.Failed("scope not available: ${e.message}")
                } catch (e: IOException) {
                    // I/O failure inside the atomic publish (disk error, unsupported atomic move):
                    // sanitized — the raw message may carry real paths (doc 10).
                    ToolExecutorResult.Failed("workspace I/O failure; the edit was not performed")
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

    /** A parsed `edit` argument set, or null when a required field is missing or malformed. */
    private data class Parsed(
        val path: FileScopePath,
        val oldText: String,
        val newText: String,
        val expectedSha256: String,
        val replaceAll: Boolean,
    )

    @Suppress("SwallowedException") // a bad reference / hash is deliberately null (sanitized)
    private fun parseArgs(args: JsonObject): Parsed? {
        val ref = args["path"]?.jsonPrimitive?.content
        val oldText = args["oldText"]?.jsonPrimitive?.content
        val newText = args["newText"]?.jsonPrimitive?.content
        val expected = args["expectedSha256"]?.jsonPrimitive?.content
        val path = ref?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        // One combined guard: every required field present AND the hash well-formed. The
        // `!!` below is safe — `complete` proves each value non-null.
        val complete =
            ref != null &&
                oldText != null &&
                newText != null &&
                expected != null &&
                path != null &&
                SHA256_HEX.matches(expected)
        if (!complete) return null
        val replaceAll = boolValue(args["replaceAll"]) ?: false
        return Parsed(path!!, oldText!!, newText!!, expected!!, replaceAll)
    }

    private fun boolValue(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.let { p ->
            when {
                p.isString -> p.content.toBooleanStrictOrNull()
                p.content == "true" -> true
                p.content == "false" -> false
                else -> null
            }
        }

    /** Count of non-overlapping occurrences of [needle] in [haystack]. */
    private fun countNonOverlapping(
        haystack: String,
        needle: String,
    ): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (from <= haystack.length - needle.length) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) break
            count++
            from = i + needle.length
        }
        return count
    }

    /**
     * The pre-decode probe gate, fail-closed: a missing file, a file too large to decode whole
     * (the full-content decode holds it in memory — the quota is not an in-memory bound, so an
     * oversized file is a stable refusal, not an OOM), and an encoding that cannot round-trip
     * through read/replace/re-encode. An EMPTY file passes — the match check fails closed.
     */
    private fun probeRefusal(
        probe: ContentProbe.Result,
        ref: String,
    ): ToolExecutorResult? =
        when {
            probe.sizeBytes < 0 -> {
                ToolExecutorResult.Failed("file not found: $ref")
            }

            probe.sizeBytes > MAX_EDITABLE_BYTES -> {
                ToolExecutorResult.Failed("file too large to edit: $ref")
            }

            probe.encoding !in TEXT_ENCODINGS -> {
                ToolExecutorResult.Failed(
                    "file is not UTF-8 text; use write to replace it whole: $ref",
                )
            }

            else -> {
                null
            }
        }

    /**
     * Strict whole-file UTF-8 gate. [ContentProbe] classifies only the leading 8 KiB, so a file
     * that is clean text in the prefix but carries an invalid sequence (or a NUL byte) in the
     * tail would otherwise pass the probe; decoding the FULL content with REPORT error action
     * refuses it fail-closed instead of republishing U+FFFD.
     *
     * @return the decoded text, or null when [bytes] is not acceptable UTF-8 text.
     */
    @Suppress("SwallowedException") // a decode failure is deliberately null (refusal, sanitized message)
    private fun decodeStrictUtf8Text(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** The post-probe outcome of loading a file's current content: ready text or a refusal. */
    private sealed interface CurrentContent {
        data class Ready(
            val text: String,
        ) : CurrentContent

        data class Refusal(
            val result: ToolExecutorResult,
        ) : CurrentContent
    }

    /**
     * Loads and strictly decodes the file's current content with the post-probe guards: a file
     * that vanished or grew past the cap between the probe gate and the read is refused (the cap
     * is an in-memory bound, not just a quota matter), and any non-UTF-8 content is refused
     * strict — the probe saw only an 8 KiB prefix, so a bad tail byte is refused here, not
     * republished as U+FFFD.
     */
    private fun loadCurrentContent(
        store: WorkspaceArtifactStore,
        path: FileScopePath,
        ref: String,
    ): CurrentContent {
        val fresh = store.stat(path)
        val sizeRefusal =
            when {
                fresh.sizeBytes < 0 -> "file not found: $ref"
                fresh.sizeBytes > MAX_EDITABLE_BYTES -> "file too large to edit: $ref"
                else -> null
            }
        if (sizeRefusal != null) {
            return CurrentContent.Refusal(ToolExecutorResult.Failed(sizeRefusal))
        }
        val text = decodeStrictUtf8Text(store.readAll(path))
        return if (text == null) {
            CurrentContent.Refusal(
                ToolExecutorResult.Failed("file is not UTF-8 text; use write to replace it whole: $ref"),
            )
        } else {
            CurrentContent.Ready(text)
        }
    }

    private fun output(
        path: FileScopePath,
        replacements: Int,
        outcome: WriteOutcome,
    ): JsonObject =
        buildJsonObject {
            put("path", JsonPrimitive(path.toModelReference()))
            put("replacements", JsonPrimitive(replacements.toLong()))
            put("sizeBytes", JsonPrimitive(outcome.record.sizeBytes))
            put("sha256", JsonPrimitive(outcome.record.sha256))
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
}
