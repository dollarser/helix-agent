package com.helix.app.proot

import com.helix.app.proot.LinuxRunTool.LinuxExecutor
import com.helix.app.proot.LinuxRunTool.MAX_IMPORT_BYTES
import com.helix.app.proot.LinuxRunTool.ParsedLinuxCall
import com.helix.app.proot.LinuxRunTool.failed
import com.helix.app.proot.LinuxRunTool.sha256Hex
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/**
 * The production [LinuxExecutor]: availability gate → input snapshot (files → zip →
 * PFD) → environment screening (main process) → submit (fresh cold bind, re-handshaked)
 * → bounded wait (journal query ONLY after any loss — never a replay) → hash-verified
 * output import into the Workspace `output/` region. Runs on the dispatcher's executor
 * thread (blocking, deadline-bounded).
 */
@Suppress("TooManyFunctions") // one step per job phase (gate/snapshot/screen/submit/wait/import)
internal class LinuxJobExecution(
    private val client: ProotJobClient,
    private val gate: () -> LinuxRuntimeGate,
    private val store: WorkspaceArtifactStore,
    private val scratchRoot: File,
    private val jobIdProvider: () -> String,
    private val knownSecretValues: () -> Set<String>,
    private val beforeSubmit: (ParsedLinuxCall, ProotJobSpec) -> Unit,
    private val persistVerifiedResult: (ParsedLinuxCall, ProotJobRecord, File) -> Unit,
) : LinuxExecutor {
    private val inputSnapshot = LinuxInputSnapshot(store)

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
                    " — repair it from the in-app Runtime entry after a manual user " +
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
                "the installed Runtime no longer matches the verified version — " +
                    "verify or repair Runtime after a manual user action; " +
                    "the next retry creates a new ToolCall/approval/jobId."
            }

            else -> {
                "PRoot Runtime connection failed: " + cause.name +
                    " — repair it from the in-app Runtime entry; nothing was submitted."
            }
        }

    private fun submissionFailure(cause: com.helix.runtime.proot.ipc.UnavailableCause): ToolExecutorResult.Failed {
        val uncertain =
            cause == com.helix.runtime.proot.ipc.UnavailableCause.DEAD_OBJECT ||
                cause == com.helix.runtime.proot.ipc.UnavailableCause.PROTOCOL_MISMATCH
        return failed(
            if (uncertain) {
                "the Runtime submission result is unknown — reconcile the original job; nothing was replayed."
            } else {
                unavailableDetail(cause)
            },
            unavailableCode(cause),
            sideEffectFree = !uncertain,
            requiresReview = uncertain,
        )
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
                inputSnapshot.build(call.inputReferences, inputZip)
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
        // Persist identity before any submit transaction; failure here prevents submission.
        beforeSubmit(call, spec)
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
                return submissionFailure(submit.cause)
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
                    "the job did not settle within the wait window (interrupted; it may still be " +
                        "running in the Runtime) — reconcile by job id; nothing was replayed.",
                    "INTERRUPTED_TIMEOUT",
                    sideEffectFree = false,
                    requiresReview = true,
                )
            }

            is ProotJobClient.AwaitOutcome.Unavailable -> {
                return failed(
                    "the Runtime became unreachable while waiting (interrupted): " + outcome.cause.name +
                        " — reconcile by job id; nothing was replayed.",
                    "INTERRUPTED_" + outcome.cause.name,
                    sideEffectFree = false,
                    requiresReview = true,
                )
            }

            is ProotJobClient.AwaitOutcome.Unknown -> {
                return failed(
                    "the Runtime no longer knows this job id (journal evicted); parked INTERRUPTED.",
                    "INTERRUPTED_UNKNOWN",
                    sideEffectFree = false,
                    requiresReview = true,
                )
            }

            is ProotJobClient.AwaitOutcome.Interrupted -> {
                return failed(
                    "the job's evidence expired before reconciliation (30-day retention); parked INTERRUPTED.",
                    "INTERRUPTED_EVIDENCE_EXPIRED",
                    sideEffectFree = false,
                    requiresReview = true,
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
                sideEffectFree = false,
            )
        }
        if (!outputZip.isFile || outputZip.length() == 0L) {
            return failed(
                "the output archive was not delivered (empty output PFD).",
                "OUTPUT_MISSING",
                sideEffectFree = false,
                requiresReview = true,
            )
        }
        val expectedManifestSha =
            record.outputManifestSha256
                ?: return failed(
                    "the terminal record carries no output manifest hash.",
                    "OUTPUT_MANIFEST_MISSING",
                    sideEffectFree = false,
                    requiresReview = true,
                )
        val extraction =
            try {
                ZipJobExtractor.extract(outputZip, File(scratch, "extracted"))
            } catch (e: JobArchiveException) {
                return failed(
                    "the output archive failed verification: ${e.message?.take(120)}",
                    "OUTPUT_VERIFY_FAILED",
                    sideEffectFree = false,
                    requiresReview = true,
                )
            }
        // The extraction's manifest hash is the CANONICAL manifest-document hash — it is
        // the SAME value the companion's terminal record carries (outputManifestSha256):
        // any tampering between the verified commit and the archive delivery breaks it.
        if (extraction.manifestSha256 != expectedManifestSha) {
            return failed(
                "the output archive hash does not match the verified terminal record.",
                "OUTPUT_HASH_MISMATCH",
                sideEffectFree = false,
                requiresReview = true,
            )
        }
        try {
            persistVerifiedResult(call, record, outputZip)
        } catch (e: Exception) {
            return failed(
                "the verified result could not be saved; recover the original job.",
                "OUTPUT_PERSIST_FAILED",
                sideEffectFree = false,
                requiresReview = true,
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
                        sideEffectFree = false,
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
                LinuxRuntimeGate.NOT_INSTALLED -> "not installed"
                LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> "disabled or force-stopped"
                LinuxRuntimeGate.NOT_VERIFIED -> "never zero-Job verified"
            }
}
