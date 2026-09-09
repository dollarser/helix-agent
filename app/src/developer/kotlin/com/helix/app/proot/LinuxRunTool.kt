package com.helix.app.proot

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * The stable availability gate of the `code.linux.run` tool (HXA-085, roadmap §12):
 *
 * - `READY`: the companion is installed AND enabled AND the user has ALREADY completed
 *   the zero-Job signature/protocol/ABI verification (the persisted anchor exists).
 *   A pre-alive process or a manually opened repair activity is NOT a condition
 *   (ADR-0007: no liveness-based admission).
 * - `NOT_INSTALLED` / `DISABLED_OR_FORCED_STOPPED` / `NOT_VERIFIED`: the stable refusal
 *   lines the UI distinguishes (未安装 / 被禁用或强制停止 / 未验证). "需更新" (the installed
 *   companion's lock no longer matches the persisted anchor) surfaces at execution time
 *   as `LOCK_MISMATCH` → the stable `NEEDS_UPDATE` failure; the passive Registry refresh
 *   never re-binds, so there is no silent background re-verification.
 *
 * The gate is evaluated PER EXECUTION by the executor (cheap PackageManager lookups +
 * one file read; NO bind). Registration is static per build flavor (developer only):
 * the tool is in the model tool table because it is registered, and the FIRST execution
 * after approval re-handshakes through the supervisor — the approval binds the exact
 * argv/script/arguments, never a live process.
 */
enum class LinuxRuntimeGate {
    READY,
    NOT_INSTALLED,
    DISABLED_OR_FORCED_STOPPED,
    NOT_VERIFIED,
}

/**
 * The `code.linux.run` built-in tool (roadmap HXA-085): runs an Agent-proposed Linux
 * command in the separately signed, OFFLINE PRoot Runtime (doc local-code-execution §6.5–6.7).
 *
 * Contract:
 * - [ToolOperationClass.CODE_EXECUTION], base risk [RiskLevel.L2]: PER-CALL approval
 *   ("每次审批"); a generic L2 — no Trusted Workspace / batch / auto-run path can mint
 *   approval for it (ADR-0012: generic L2 stays bound to exact ToolCalls).
 * - [ExecutionTargetType.LOCAL_PROOT]: the Policy Engine's `ISOLATED_RUNTIME_REQUIRES_ADVANCED`
 *   denial is the STANDARD-profile gate (ADR-0005); the framework's `lane:proot` serializes
 *   executions to single concurrency and CODE_EXECUTION is exclusive — two Linux calls
 *   never run in parallel and never overlap a file tool's effect window.
 * - NO INTERNET, ever: the Runtime APK declares no INTERNET permission; NEITHER the
 *   Advanced profile NOR a LAN scope can add it (§6.1 product boundary — the descriptor
 *   has no network switch because there is none).
 * - The input is a bounded, explicit snapshot: `files` are workspace references COPIED
 *   into the job input archive; the real Workspace directory is NEVER mounted. The output
 *   comes back as a hash-verified archive; only the explicitly requested `output`
 *   reference receives the verified `result.txt` (the Runtime never writes the Workspace).
 * - Secret inheritance is impossible by construction: `environment` is screened in the
 *   MAIN process by [ProotEnvScreen] (allowlist + secret-name pattern + KNOWN SecretStore
 *   values + auth-structure shapes) before the wire; the Runtime gains no SecretStore
 *   access and inherits neither the main process environment nor Provider credentials.
 * - The executor NEVER replays: a binder loss after submission settles from the journal
 *   by job id (query/reconcile only) or reports a stable INTERRUPTED failure.
 */
object LinuxRunTool {
    const val NAME: String = "code.linux.run"

    /**
     * The minimum future distance of a job's kill deadline (the wire invariant: the
     * companion's watchdog kills the process group AT the deadline, so it must still be
     * in the future at submit time).
     */
    internal const val MIN_JOB_DEADLINE_MS: Long = 1_000L

    const val VERSION: Int = 1

    /** Fixed execution budget; the model may lower (per call) but never raise it. */
    const val DEFAULT_DEADLINE_SECONDS: Long = 60

    /** Framework hard bound: the job deadline + the client's own wait grace. */
    private const val TOOL_TIMEOUT_SECONDS: Long = DEFAULT_DEADLINE_SECONDS + 60

    private const val MAX_SCRIPT_CHARS: Int = 8192

    private const val ENV_MAX_ENTRIES: Int = 64

    private const val MAX_FILES_PER_JOB: Int = 64

    /** Bounded model-visible capture import + the per-output-file import cap. */
    internal const val MAX_IMPORT_BYTES: Long = 1L * 1024L * 1024L

    /**
     * The registered contract. Input: EXACTLY ONE of `argv` / `script`, optional
     * `cwd` (a single directory name inside the job workspace), optional `environment`
     * (screened), `files` (workspace references copied in as the input snapshot),
     * optional `output` (a workspace reference the verified `result.txt` lands in),
     * optional `timeoutSeconds` (≤ [DEFAULT_DEADLINE_SECONDS]).
     */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Run ONE Linux command in the offline PRoot Runtime (separate app, NO " +
                    "network, no access to app data outside the explicit `files` input " +
                    "snapshot). `argv` runs a program with arguments; `script` runs an " +
                    "explicit shell script in /bin/sh (shell syntax only when genuinely " +
                    "needed). Output: verified stdout/stderr, the exit code, and the " +
                    "imported result file when `output` is set. Requires the ADVANCED " +
                    "profile and a previously verified PRoot Runtime; every call needs " +
                    "user approval.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.CODE_EXECUTION,
            baseRisk = RiskLevel.L2,
            timeout = TOOL_TIMEOUT_SECONDS.seconds,
            maxOutputBytes = 256L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_PROOT,
            origin = ToolOrigin.BuiltInOrigin,
        )

    @Suppress("LongMethod") // the schema is the contract: every field is a review surface
    private fun inputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("maxItems", JsonPrimitive(32))
                            put(
                                "description",
                                JsonPrimitive(
                                    "The program and its arguments (NOT a shell line: " +
                                        "no quoting, no pipes). Mutually exclusive with `script`.",
                                ),
                            )
                        },
                    )
                    put(
                        "script",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(MAX_SCRIPT_CHARS))
                            put(
                                "description",
                                JsonPrimitive(
                                    "An explicit shell script (run by /bin/sh), only when " +
                                        "shell syntax was genuinely needed. Mutually " +
                                        "exclusive with `argv`. The FULL script is shown " +
                                        "on the approval card.",
                                ),
                            )
                        },
                    )
                    put(
                        "cwd",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(128))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional working directory inside the job workspace " +
                                        "(a single directory name, e.g. \"src\").",
                                ),
                            )
                        },
                    )
                    put(
                        "environment",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional environment variables. Only the allowlist " +
                                        "(HOME, PATH, LANG, LC_ALL, TMPDIR, TERM, USER, " +
                                        "SHELL) plus explicitly allowed task variables " +
                                        "pass; secret-named, secret-valued or " +
                                        "credential-shaped entries are refused before " +
                                        "the wire.",
                                ),
                            )
                        },
                    )
                    put(
                        "files",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("maxItems", JsonPrimitive(MAX_FILES_PER_JOB))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Workspace references (scope:<scopeId>:<path>) copied " +
                                        "into the job as a bounded input snapshot; the " +
                                        "real Workspace is never mounted.",
                                ),
                            )
                        },
                    )
                    put(
                        "output",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional workspace reference (output/ region) where " +
                                        "the verified result file `result.txt` is " +
                                        "imported after the job succeeds.",
                                ),
                            )
                        },
                    )
                    put(
                        "timeoutSeconds",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(DEFAULT_DEADLINE_SECONDS))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional per-call deadline in seconds (≤ " +
                                        DEFAULT_DEADLINE_SECONDS.toString() + "); the job's process " +
                                        "group is killed when it passes.",
                                ),
                            )
                        },
                    )
                },
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    @Suppress("LongMethod") // the schema is the contract: every field is a review surface
    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "state",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("SUCCEEDED or a stable failure code."))
                        },
                    )
                    put(
                        "exitCode",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("The process exit code (0 on success)."))
                        },
                    )
                    put(
                        "stdout",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Captured stdout (bounded)."))
                        },
                    )
                    put(
                        "stderr",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Captured stderr (bounded)."))
                        },
                    )
                    put(
                        "outputImported",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("True when `output` was set and the result was imported."))
                        },
                    )
                    put(
                        "outputSha256",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("SHA-256 of the imported result file (empty when none)."),
                            )
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("state"),
                        JsonPrimitive("exitCode"),
                        JsonPrimitive("stdout"),
                        JsonPrimitive("stderr"),
                        JsonPrimitive("outputImported"),
                        JsonPrimitive("outputSha256"),
                    ),
                ),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    /**
     * The fully validated execution view of one call (the approval hash already bound the
     * raw arguments; this is what runs). [environment] is still the RAW model values — the
     * production executor screens it before the wire; tests verify the screen call.
     */
    data class ParsedLinuxCall(
        val toolCallId: String,
        val turnId: String?,
        val command: ProotJobCommand,
        val cwd: String,
        val environment: Map<String, String>,
        val inputReferences: List<String>,
        val outputReference: String?,
        /** The APPROVAL-BOUND absolute deadline (epoch ms) — the job's kill deadline. */
        val deadlineEpochMs: Long,
    )

    /**
     * The execution seam (production wires the real [ProotJobClient] + [WorkspaceArtifactStore]
     * + gate; tests inject fakes). One call = one job = one stable result; the seam NEVER
     * retries and never fakes success.
     */
    fun interface LinuxExecutor {
        fun execute(
            call: ParsedLinuxCall,
            isCancelled: () -> Boolean,
        ): ToolExecutorResult
    }

    /**
     * The implementation bound to [descriptor]. The runner (production or test seam) is
     * responsible for the [ProotJobSpec] deadline: the framework's [ToolDescriptor.timeout]
     * is the hard outer bound, and the job deadline stays ≤ the per-call `timeoutSeconds`.
     */
    fun executor(runner: LinuxExecutor): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return when (val parsed = parsed(call)) {
                    is ParsedResult.ParseFailure -> failed(parsed.detail)
                    is ParsedResult.Ok -> runner.execute(parsed.call, { call.cancel.isCancelled() })
                }
            }
        }

    /**
     * Parses + validates the call arguments into the execution view, or a stable
     * [ParseFailure] (rendered as a side-effect-free [ToolExecutorResult.Failed] by
     * [executor] — nothing touched the Runtime and no journal entry was created).
     */
    sealed interface ParsedResult {
        data class Ok(
            val call: ParsedLinuxCall,
        ) : ParsedResult

        data class ParseFailure(
            val detail: String,
        ) : ParsedResult
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    fun parsed(call: ExecutableToolCall): ParsedResult {
        val args = call.args
        val argv = (args["argv"] as? JsonArray)?.map { (it as JsonPrimitive).content }
        val script = (args["script"] as? JsonPrimitive)?.content
        val command =
            when {
                argv != null && script != null -> {
                    return ParsedResult.ParseFailure("invalid arguments: `argv` and `script` are mutually exclusive")
                }

                argv != null -> {
                    if (argv.isEmpty() || argv.any { it.isBlank() }) {
                        return ParsedResult.ParseFailure("invalid arguments: `argv` entries must be non-blank")
                    }
                    ProotJobCommand.Argv(argv)
                }

                script != null -> {
                    if (script.isBlank()) {
                        return ParsedResult.ParseFailure("invalid arguments: `script` must not be blank")
                    }
                    ProotJobCommand.Script(script)
                }

                else -> {
                    return ParsedResult.ParseFailure("invalid arguments: exactly one of `argv` or `script` is required")
                }
            }
        val cwd = (args["cwd"] as? JsonPrimitive)?.content.orEmpty()
        if (cwd.isNotEmpty()) {
            if (cwd.length > 128 || cwd.any { it == '/' || it == '\\' } || cwd in setOf(".", "..") ||
                !cwd.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            ) {
                return ParsedResult.ParseFailure("invalid `cwd`: a single directory name is allowed")
            }
        }
        val environment =
            (args["environment"] as? JsonObject)?.mapValues { (_, v) -> (v as JsonPrimitive).content } ?: emptyMap()
        if (environment.size > ENV_MAX_ENTRIES) {
            return ParsedResult.ParseFailure("invalid `environment`: at most $ENV_MAX_ENTRIES entries are allowed")
        }
        val inputReferences = (args["files"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        if (inputReferences.size > MAX_FILES_PER_JOB) {
            return ParsedResult.ParseFailure("invalid `files`: at most $MAX_FILES_PER_JOB files are allowed")
        }
        // The deadline is bound to the CALL (approval) deadline, clamped down by the
        // model's optional `timeoutSeconds` — never extended past the approved window.
        val perCallMs =
            (args["timeoutSeconds"] as? JsonPrimitive)?.longOrNull?.let {
                if (it in 1..DEFAULT_DEADLINE_SECONDS) {
                    it * 1000L
                } else {
                    return ParsedResult.ParseFailure("invalid `timeoutSeconds`: must be 1..$DEFAULT_DEADLINE_SECONDS")
                }
            } ?: DEFAULT_DEADLINE_SECONDS * 1000L
        if (call.deadline.toEpochMilli() < System.currentTimeMillis() + MIN_JOB_DEADLINE_MS) {
            return ParsedResult.ParseFailure(
                "the approval deadline has already passed — the call must be re-approved as a NEW ToolCall",
            )
        }
        // The job's kill deadline: the SOONER of the approval (call) deadline and the
        // per-call `timeoutSeconds` window — never extended past either, always in the
        // future at submit time (the wire invariant).
        val deadlineEpochMs =
            call.deadline.toEpochMilli().coerceAtMost(System.currentTimeMillis() + perCallMs)
        return ParsedResult.Ok(
            ParsedLinuxCall(
                toolCallId = call.toolCallId,
                turnId = call.turnId,
                command = command,
                cwd = cwd,
                environment = environment,
                inputReferences = inputReferences,
                outputReference = (args["output"] as? JsonPrimitive)?.content,
                deadlineEpochMs = deadlineEpochMs,
            ),
        )
    }

    internal fun failed(detail: String): ToolExecutorResult.Failed =
        ToolExecutorResult.Failed(detail, sideEffectFree = true)

    /** The production executor's failure: detail (model-visible) + the stable code (audit). */
    internal fun failed(
        detail: String,
        code: String,
        sideEffectFree: Boolean = true,
        requiresReview: Boolean = false,
    ): ToolExecutorResult.Failed =
        ToolExecutorResult.Failed(
            detail,
            sideEffectFree = sideEffectFree,
            requiresReview = requiresReview,
            auditDetail =
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                },
        )

    /** Registers both the contract and the implementation (developer flavor only). */
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        runner: LinuxExecutor,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(runner))
    }

    // ------------------------------------------------------------------ production seam

    /** Compatibility constructor; all job execution is owned by [LinuxJobExecution]. */
    class ProductionLinuxExecutor(
        client: ProotJobClient,
        gate: () -> LinuxRuntimeGate,
        store: WorkspaceArtifactStore,
        scratchRoot: File,
        jobIdProvider: () -> String,
        knownSecretValues: () -> Set<String>,
        beforeSubmit: (ParsedLinuxCall, ProotJobSpec) -> Unit,
        persistVerifiedResult: (ParsedLinuxCall, ProotJobRecord, File) -> Unit,
    ) : LinuxExecutor by LinuxJobExecution(
            client,
            gate,
            store,
            scratchRoot,
            jobIdProvider,
            knownSecretValues,
            beforeSubmit,
            persistVerifiedResult,
        )

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    internal fun sha256Hex(bytes: ByteArray): String {
        val digest =
            try {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } catch (e: Exception) {
                error("SHA-256 unavailable")
            }
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
