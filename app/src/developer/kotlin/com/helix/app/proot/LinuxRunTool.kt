package com.helix.app.proot

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.ZipJobExtractor
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
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
    private const val MAX_IMPORT_BYTES: Long = 1L * 1024L * 1024L

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
                command = command,
                cwd = cwd,
                environment = environment,
                inputReferences = inputReferences,
                outputReference = (args["output"] as? JsonPrimitive)?.content,
                deadlineEpochMs = deadlineEpochMs,
            ),
        )
    }

    private fun failed(detail: String): ToolExecutorResult.Failed =
        ToolExecutorResult.Failed(detail, sideEffectFree = true)

    /** The production executor's failure: detail (model-visible) + the stable code (audit). */
    private fun failed(
        detail: String,
        code: String,
    ): ToolExecutorResult.Failed =
        ToolExecutorResult.Failed(
            detail,
            sideEffectFree = true,
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

    /**
     * The production [LinuxExecutor]: availability gate → input snapshot (files → zip →
     * PFD) → environment screening (main process) → submit (fresh cold bind, re-handshaked)
     * → bounded wait (journal query ONLY after any loss — never a replay) → hash-verified
     * output import into the Workspace `output/` region. Runs on the dispatcher's executor
     * thread (blocking, deadline-bounded).
     */
    @Suppress("TooManyFunctions") // one step per job phase (gate/snapshot/screen/submit/wait/import)
    class ProductionLinuxExecutor(
        private val client: ProotJobClient,
        private val gate: () -> LinuxRuntimeGate,
        private val store: WorkspaceArtifactStore,
        private val scratchRoot: File,
        private val jobIdProvider: () -> String,
        private val knownSecretValues: () -> Set<String>,
    ) : LinuxExecutor {
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
        override fun execute(
            call: ParsedLinuxCall,
            isCancelled: () -> Boolean,
        ): ToolExecutorResult {
            if (isCancelled()) return ToolExecutorResult.Cancelled
            val gateState = gate()
            if (gateState != LinuxRuntimeGate.READY) {
                return failed(
                    "PRoot Runtime unavailable: " + gateState.label +
                        " — repair it from the in-app entry (修复 Runtime) after a manual user " +
                        "action; the next retry creates a new ToolCall/approval/jobId.",
                )
            }
            // The scratch dir is per CALL (the job id is per call too, so it never
            // collides with a concurrent or retried call's scratch).
            val scratchId = (System.nanoTime() + call.deadlineEpochMs).toULong().toString(16)
            val scratch = File(scratchRoot, "call-" + scratchId)
            if (!scratch.isDirectory && !scratch.mkdirs()) {
                return failed("the job scratch area is unavailable.", "SCRATCH_FAILED")
            }
            return try {
                runJob(call, isCancelled, scratch)
            } finally {
                @Suppress("SwallowedException")
                try {
                    scratch.deleteRecursively()
                } catch (e: Exception) {
                    // best effort; the scratch dir is bounded and scratch-namespace only
                }
            }
        }

        /** The stable, user-presentable line for a connection failure (the UI's 连接失败 / 需更新 split). */
        private fun unavailableDetail(cause: com.helix.runtime.proot.ipc.UnavailableCause): String =
            when (cause) {
                com.helix.runtime.proot.ipc.UnavailableCause.LOCK_MISMATCH -> {
                    "the installed Runtime no longer matches the verified version (需更新) — " +
                        "run 验证 Runtime (or 修复 Runtime) after a manual user action; " +
                        "the next retry creates a new ToolCall/approval/jobId."
                }

                else -> {
                    "PRoot Runtime connection failed (连接失败): " + cause.name +
                        " — repair it from the in-app entry (修复 Runtime); nothing was submitted."
                }
            }

        /** The stable audit code: NEEDS_UPDATE is the 需更新 split of the connection failure. */
        private fun unavailableCode(cause: com.helix.runtime.proot.ipc.UnavailableCause): String =
            when (cause) {
                com.helix.runtime.proot.ipc.UnavailableCause.LOCK_MISMATCH -> "NEEDS_UPDATE"
                else -> "UNAVAILABLE_" + cause.name
            }

        @Suppress(
            "LongMethod",
            "CyclomaticComplexMethod",
            "NestedBlockDepth",
            "ReturnCount",
            "TooGenericExceptionCaught",
            "SwallowedException",
        )
        private fun runJob(
            call: ParsedLinuxCall,
            isCancelled: () -> Boolean,
            scratch: File,
        ): ToolExecutorResult {
            if (isCancelled()) return ToolExecutorResult.Cancelled
            // 1) Input snapshot: every listed file, read THROUGH THE STORE (containment-
            //    enforced), into one bounded zip. The real Workspace is never mounted.
            val inputZip = File(scratch, "input.zip")
            var inputSha: String?
            try {
                inputSha =
                    buildInputZip(call.inputReferences, inputZip)
            } catch (e: Exception) {
                return failed("the input snapshot could not be built: ${e.message?.take(120)}", "INPUT_BUILD_FAILED")
            }
            if (inputSha ==
                null
            ) {
                return failed(
                    "the input snapshot could not be built (size cap or missing file).",
                    "INPUT_BUILD_FAILED",
                )
            }
            // 2) Environment screening (MAIN process; the Runtime never sees secrets).
            //    The model-proposed variables are screened BEFORE the wire with the
            //    CURRENT SecretStore values + the name/shape denylists. The allowlist
            //    (HOME/PATH/LANG/LC_ALL/TMPDIR/TERM/USER/SHELL) plus the task's explicit
            //    extra names (none by default — the model may only use allowlisted names).
            val screened = ProotEnvScreen.screen(call.environment, knownSecretValues(), extraAllowedNames = emptySet())
            val environment =
                when (screened) {
                    is ProotEnvScreen.Verdict.Rejected -> {
                        val names = screened.reasons.joinToString(",") { it.name }
                        return failed("environment refused before the wire: $names", "ENV_REFUSED")
                    }

                    is ProotEnvScreen.Verdict.Approved -> {
                        screened.environment
                    }
                }
            // 3) Spec (the command + arguments are exactly what the approval bound). The
            //    wire deadlineMs is a RELATIVE budget (the companion computes
            //    createdAt + deadlineMs as the kill deadline), so pass the REMAINING
            //    approved window, not the absolute epoch: the companion's watchdog then
            //    kills the process group exactly when the approval-BOUND deadline lapses.
            val remainingMs =
                (call.deadlineEpochMs - System.currentTimeMillis()).coerceIn(1_000L, 3_600_000L)
            val spec =
                ProotJobSpec(
                    executionId =
                        "exec_" + System.currentTimeMillis().toULong().toHexString() +
                            (System.nanoTime() and 0xFFFFFFFFL).toULong().toHexString(),
                    jobId = jobIdProvider(),
                    command = call.command,
                    relativeWorkingDirectory = call.cwd,
                    environment = environment,
                    deadlineMs = remainingMs,
                    maxOutputBytes = 8L * 1024L * 1024L,
                    inputManifestSha256 = inputSha,
                )
            // 4) Submit: PFDs handed to the client; it owns them in EVERY outcome.
            val outputZip = File(scratch, "output.zip")
            val inputPfd =
                android.os.ParcelFileDescriptor.open(
                    inputZip,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            val outputPfd =
                android.os.ParcelFileDescriptor.open(
                    outputZip,
                    android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                )
            when (val submit = client.submit(spec, inputPfd, outputPfd)) {
                is ProotJobClient.SubmitOutcome.Unavailable -> {
                    return failed(
                        unavailableDetail(submit.cause),
                        unavailableCode(submit.cause),
                    )
                }

                is ProotJobClient.SubmitOutcome.Rejected -> {
                    return failed("the Runtime refused the job: " + submit.refusal.wire, submit.refusal.wire)
                }

                is ProotJobClient.SubmitOutcome.Accepted,
                is ProotJobClient.SubmitOutcome.Duplicate,
                -> {}
            }
            if (isCancelled()) {
                client.cancel(spec.jobId)
                return ToolExecutorResult.Cancelled
            }
            // 5) Wait: bounded polling; a binder loss never replays the job (ADR-0007).
            // The wait window is the REMAINING time until the bound deadline plus a short
            // grace for the terminal commit (the companion's watchdog kills the process
            // group AT the deadline, so the terminal state is committed shortly after).
            val waitMs =
                (call.deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(0L) + 30_000L
            val outcome =
                client.awaitTerminal(spec.jobId, pollIntervalMs = 500L, timeoutMs = waitMs) {
                    !isCancelled()
                }
            when (outcome) {
                is ProotJobClient.AwaitOutcome.TimedOut -> {
                    return failed(
                        "the job did not settle within the wait window (执行中断; it may still be " +
                            "running in the Runtime) — reconcile by job id; nothing was replayed.",
                        "INTERRUPTED_TIMEOUT",
                    )
                }

                is ProotJobClient.AwaitOutcome.Unavailable -> {
                    return failed(
                        "the Runtime became unreachable while waiting (执行中断): " + outcome.cause.name +
                            " — reconcile by job id; nothing was replayed.",
                        "INTERRUPTED_" + outcome.cause.name,
                    )
                }

                is ProotJobClient.AwaitOutcome.Unknown -> {
                    return failed(
                        "the Runtime no longer knows this job id (journal evicted); parked INTERRUPTED.",
                        "INTERRUPTED_UNKNOWN",
                    )
                }

                is ProotJobClient.AwaitOutcome.Interrupted -> {
                    return failed(
                        "the job's evidence expired before reconciliation (30-day retention); parked INTERRUPTED.",
                        "INTERRUPTED_EVIDENCE_EXPIRED",
                    )
                }

                is ProotJobClient.AwaitOutcome.Terminal -> {}
            }
            // 6) Terminal: verify the record + import the hash-verified output archive.
            val record = (outcome as ProotJobClient.AwaitOutcome.Terminal).record
            if (record.state != com.helix.runtime.proot.ipc.ProotJobState.SUCCEEDED) {
                return failed(
                    "the job ended " + record.state.wire + (record.exitCode?.let { " (exit code $it)" }.orEmpty()),
                    "JOB_" + record.state.wire,
                )
            }
            if (!outputZip.isFile || outputZip.length() == 0L) {
                return failed("the output archive was not delivered (empty output PFD).", "OUTPUT_MISSING")
            }
            val expectedManifestSha =
                record.outputManifestSha256
                    ?: return failed("the terminal record carries no output manifest hash.", "OUTPUT_MANIFEST_MISSING")
            val extraction =
                try {
                    ZipJobExtractor.extract(outputZip, File(scratch, "extracted"))
                } catch (e: JobArchiveException) {
                    return failed(
                        "the output archive failed verification: ${e.message?.take(120)}",
                        "OUTPUT_VERIFY_FAILED",
                    )
                }
            // The extraction's manifest hash is the CANONICAL manifest-document hash — it is
            // the SAME value the companion's terminal record carries (outputManifestSha256):
            // any tampering between the verified commit and the archive delivery breaks it.
            if (extraction.manifestSha256 != expectedManifestSha) {
                return failed(
                    "the output archive hash does not match the verified terminal record.",
                    "OUTPUT_HASH_MISMATCH",
                )
            }
            var outputImported = false
            var outputSha = ""
            call.outputReference?.let { reference ->
                val imported = importResult(extraction, expectedManifestSha, scratch)
                if (imported == null) {
                    // The job SUCCEEDED but wrote no `result.txt` (or the result exceeded
                    // the import cap): the streams are still reported; the import simply
                    // did not happen. A missing file is a finding, not a failure.
                } else {
                    try {
                        importInto(reference, imported)
                    } catch (e: IllegalArgumentException) {
                        return failed(
                            "the result could not be imported into the Workspace: ${e.message?.take(120)}",
                            "OUTPUT_IMPORT_FAILED",
                        )
                    }
                    outputImported = true
                    outputSha = imported.second
                }
            }
            return ToolExecutorResult.Completed(
                output =
                    buildJsonObject {
                        put("state", JsonPrimitive("SUCCEEDED"))
                        put("exitCode", JsonPrimitive(record.exitCode ?: 0))
                        put("stdout", JsonPrimitive(readBounded(File(File(scratch, "extracted"), "stdout.txt"))))
                        put("stderr", JsonPrimitive(readBounded(File(File(scratch, "extracted"), "stderr.txt"))))
                        put("outputImported", JsonPrimitive(outputImported))
                        put("outputSha256", JsonPrimitive(outputSha))
                    },
                auditDetail =
                    buildJsonObject {
                        put("jobId", JsonPrimitive(spec.jobId))
                        put("executionId", JsonPrimitive(spec.executionId))
                        put("inputSha256", JsonPrimitive(inputSha))
                        put("outputManifestSha256", JsonPrimitive(expectedManifestSha))
                        put("deadlineMs", JsonPrimitive(call.deadlineEpochMs))
                    },
            )
        }

        /**
         * Imports the verified result file into the Workspace: the region is the file's OWN
         * region (the store enforces containment); the model may point at work/ or output/ —
         * the Runtime never writes anywhere, only the verified result file is imported,
         * through the store.
         */
        private fun importInto(
            reference: String,
            imported: Pair<ByteArray, String>,
        ) {
            val path =
                try {
                    FileScopePath.fromModelReference(reference)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "invalid `output` reference: ${e.message?.take(120)}",
                        e,
                    )
                }
            val region =
                path.relativePath
                    .split('/')
                    .firstOrNull()
                    ?.takeIf { it in WorkspaceLayout.regions }
                    ?: throw IllegalArgumentException("no region for ${path.relativePath}")
            store.writeArtifact(path, imported.first, region)
        }

        /**
         * Builds the bounded input zip from the model references (read through the store);
         * returns the manifest SHA-256. Basenames are deduplicated inside the archive (the
         * Workspace directory structure is not revealed to the guest).
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
        private fun buildInputZip(
            references: List<String>,
            target: File,
        ): String? {
            if (references.isEmpty()) {
                // An empty input archive is still a valid, hashable snapshot.
                return buildZip(target, emptyList())
            }
            val entries =
                references.mapIndexed { index, ref -> loadInputEntry(index, ref) }
            return buildZip(target, entries)
        }

        /**
         * Loads one input reference THROUGH THE STORE (containment-enforced) with the
         * per-file cap; the archive name is the basename, deduplicated by index (the
         * Workspace directory structure is not revealed to the guest).
         */
        @Suppress("TooGenericExceptionCaught")
        private fun loadInputEntry(
            index: Int,
            ref: String,
        ): Triple<String, ByteArray, String> {
            val path =
                try {
                    FileScopePath.fromModelReference(ref)
                } catch (e: IllegalArgumentException) {
                    throw JobArchiveException(
                        "invalid input reference: ${e.message?.take(120)}",
                        e,
                    )
                }
            val bytes = store.readAll(path)
            if (bytes.isEmpty() || bytes.size.toLong() > MAX_IMPORT_BYTES) {
                throw JobArchiveException(
                    "input file is missing, empty or exceeds the per-file cap: " + path.toModelReference(),
                )
            }
            val name = path.name + (if (index == 0) "" else "-" + (index + 1))
            return Triple(name, bytes, sha256Hex(bytes))
        }

        /**
         * One bounded zip: manifest first (canonical), then entries; caps enforced by the
         * writer. Any failure returns null (the caller maps it to the stable
         * `INPUT_BUILD_FAILED` — no partial artifact is submitted).
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun buildZip(
            target: File,
            entries: List<Triple<String, ByteArray, String>>,
        ): String? {
            if (target.parentFile != null) target.parentFile.mkdirs()
            return try {
                var sha: String? = null
                FileOutputStream(target).use { out ->
                    JobZipWriter(out).use { writer ->
                        val manifest =
                            JobManifest(
                                entries
                                    .map { (name, bytes, digest) ->
                                        JobManifestEntry(name, digest, bytes.size.toLong())
                                    }.sortedBy { it.path },
                            )
                        writer.writeManifest(JobManifestCodec.encode(manifest))
                        sha = sha256Hex(JobManifestCodec.encode(manifest).encodeToByteArray())
                        entries.forEachIndexed { index, (name, bytes, _) ->
                            val entryFile = File(target.parentFile, "in-$index.tmp")
                            Files.write(entryFile.toPath(), bytes)
                            try {
                                writer.writeEntry(name, entryFile)
                            } finally {
                                entryFile.delete()
                            }
                        }
                    }
                }
                sha
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Imports the verified `result.txt`: present in the extraction, size-capped, and its
         * hash equal to the manifest entry's hash (the manifest itself was hash-verified
         * against the terminal record). Returns (bytes, sha) or null.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
        private fun importResult(
            extraction: ZipJobExtractor.Extraction,
            expectedManifestSha: String,
            scratch: File,
        ): Pair<ByteArray, String>? {
            val extractedDir = File(scratch, "extracted")
            val file = File(extractedDir, "result.txt")
            if (!file.isFile) return null
            if (file.length() > MAX_IMPORT_BYTES) return null
            val bytes = Files.readAllBytes(file.toPath())
            val sha = sha256Hex(bytes)
            // Defense in depth on top of the whole-manifest hash check already performed:
            // the extracted manifest's own entry for result.txt must match the bytes.
            if (extraction.manifestSha256 != expectedManifestSha) return null
            val entry = extraction.manifest.entries.firstOrNull { it.path == "result.txt" } ?: return null
            if (entry.sha256 != sha || entry.size != bytes.size.toLong()) return null
            return bytes to sha
        }

        /**
         * The bounded model-visible capture: ≤ [MAX_IMPORT_BYTES] (the import cap, 1 MiB),
         * truncated from the tail (the newest bytes are what a debugging model needs; the
         * full capture remains in the Runtime journal, reconcilable by job id).
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun readBounded(file: File): String {
            if (!file.isFile) return ""
            val bytes = Files.readAllBytes(file.toPath())
            val slice =
                if (bytes.size > MAX_IMPORT_BYTES) {
                    bytes.copyOfRange(bytes.size - MAX_IMPORT_BYTES.toInt(), bytes.size)
                } else {
                    bytes
                }
            return String(slice, Charsets.UTF_8)
        }

        private val LinuxRuntimeGate.label: String
            get() =
                when (this) {
                    LinuxRuntimeGate.READY -> "ready"
                    LinuxRuntimeGate.NOT_INSTALLED -> "未安装 (not installed)"
                    LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> "被禁用/强制停止 (disabled or force-stopped)"
                    LinuxRuntimeGate.NOT_VERIFIED -> "未验证 (never zero-Job verified)"
                }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun sha256Hex(bytes: ByteArray): String {
        val digest =
            try {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } catch (e: Exception) {
                error("SHA-256 unavailable")
            }
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
