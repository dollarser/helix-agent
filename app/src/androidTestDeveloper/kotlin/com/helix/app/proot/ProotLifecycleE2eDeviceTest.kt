package com.helix.app.proot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HXA-086 lifecycle matrix, main-app side. The host-side states an app
 * process cannot create (fresh reinstall, idle-kill, force-stop, mid-job
 * kills, wake-lock sampling) are driven by `scripts/accept-hxa-086-lifecycle.sh`,
 * which runs these methods as phases. Every phase also runs standalone in
 * the full suite: the kill-bracketed phases are assumed off without their
 * host marker arguments, and the state phases assert against the OBSERVED
 * companion state (dual branch).
 *
 * The long-job phases write `LIFECYCLE-JOB-ID` (the job id) into the app's
 * filesDir — same uid as the companion, host-readable by the script — to feed
 * the post-death reconciliation phase (`phaseQueryTheOrphanedJobByJobId`, job
 * id passed as the `job_id` argument).
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions")
class ProotLifecycleE2eDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val supervisor = ProotRuntimeSupervisor(context)
    private val jobClient = ProotJobClient(supervisor)
    private val counter = AtomicLong(0)
    private val scratch = mutableListOf<File>()

    @Before
    fun warm() {
        org.junit.Assume.assumeTrue(
            "companion not installed — install runtime/proot-app/.../proot-app-debug.apk",
            companionInstalled(context),
        )
        // The zero-Job verification (the user's one-time settings action) is
        // only possible/needed while the companion is not force-stopped: a
        // force-stopped companion is the REFUSAL subject of one of the phases
        // below (the script creates that state).
        if (!probeStoppedState(context)) {
            runOnWorker(timeoutMs = 90_000L) {
                val result = supervisor.verify(System.currentTimeMillis())
                assertTrue(
                    "the zero-Job verification must succeed on a warm companion: $result",
                    result is com.helix.runtime.proot.ipc.ProotRuntimeAvailability.Verified,
                )
                Unit
            }
        }
    }

    @After
    fun cleanScratch() {
        scratch.forEach { it.deleteRecursively() }
        scratch.clear()
        ranMarker?.delete()
        ranMarker = null
    }

    // ------------------------------------------------------------------ phases

    /** The first job after a clean (never-started-this-session) companion: cold bind. */
    @Test
    fun phaseFirstJobAfterCleanStateRunsByColdBind() {
        runOnWorker(timeoutMs = 180_000L) {
            val store = e2eWorkspaceStore()
            markRan("e2e-lc-ran-cold")
            val args =
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonArray {
                            add(JsonPrimitive("/bin/sh"))
                            add(JsonPrimitive("-c"))
                            add(JsonPrimitive("echo COLD-BIND-OK"))
                        },
                    )
                    put("timeoutSeconds", JsonPrimitive(60))
                }
            val completed =
                LinuxRunTool.executor(productionExecutor(store)).execute(linuxCall("tc-lc-cold-", args))
                    as ToolExecutorResult.Completed
            assertEquals(
                "SUCCEEDED",
                completed.output.jsonObject["state"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(
                completed.output.jsonObject["stdout"]!!
                    .jsonPrimitive.content
                    .contains("COLD-BIND-OK"),
            )
            Unit
        }
    }

    /**
     * Companion-state phase. The gate is the REAL production gate (no
     * injection): a force-stopped companion (fresh install / user
     * force-stop — created by the acceptance script) must be stably REFUSED
     * with the unavailable detail, never silently re-bound or repaired in the
     * background; a live companion must run the trivial job. The refusal is
     * asserted against the OBSERVED state, so the phase is honest in both
     * the script bracket and a plain full-suite run.
     */
    @Test
    fun phaseForceStoppedCompanionIsStablyRefusedForJobs() {
        runOnWorker(timeoutMs = 120_000L) {
            val store = e2eWorkspaceStore()
            markRan("e2e-lc-ran-fs")
            val args =
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonArray { add(JsonPrimitive("true")) },
                    )
                    put("timeoutSeconds", JsonPrimitive(30))
                }
            val result =
                LinuxRunTool
                    .executor(productionExecutor(store, gate = { supervisorGate() }))
                    .execute(linuxCall("tc-lc-fs-", args))
            if (probeStoppedState(context)) {
                val failed =
                    result as? ToolExecutorResult.Failed
                        ?: error("a force-stopped companion must be refused, got: $result")
                assertTrue(
                    "the refusal must be the stable unavailable line (user-gated recovery), not a crash: $failed",
                    "unavailable" in failed.detail && "force-stopped" in failed.detail,
                )
            } else {
                // Live companion: the same trivial job must run (no false refusal).
                val completed =
                    result as? ToolExecutorResult.Completed
                        ?: error("a live companion must run the job, got: $result")
                assertEquals(
                    "SUCCEEDED",
                    completed.output.jsonObject["state"]!!
                        .jsonPrimitive.content,
                )
            }
            Unit
        }
    }

    /**
     * Submits a 90 s job, confirms it is RUNNING, prints its id and RETURNS.
     * The instrumentation run ending force-stops the main app (unbind, no FGS):
     * the host observes the job's fate and reconciles by job id afterwards.
     */
    @Test
    fun phaseLongJobThenReturnWithJobId() {
        runOnWorker(timeoutMs = 120_000L) {
            markRan("e2e-lc-ran-long")
            // The executor waits for the terminal state; we must NOT wait — a
            // raw client submit is the right tool for the host-bracketed phase.
            val (spec, specArchive) = sleepSpec(45L)
            val (inputPfd, outputPfd) = pfdPair(specArchive)
            val submit = jobClient.submit(spec, inputPfd, outputPfd)
            val accepted =
                submit as? com.helix.runtime.proot.client.ProotJobClient.SubmitOutcome.Accepted
                    ?: error("the job must be accepted, got: $submit")
            val jobId = accepted.record.jobId
            // Confirm RUNNING before returning (the host then kills the main app).
            val start = System.currentTimeMillis()
            var state = accepted.record.state
            while (state == com.helix.runtime.proot.ipc.ProotJobState.PENDING) {
                val q = jobClient.query(jobId)
                if (q is com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome.Ok) state = q.record.state
                if (System.currentTimeMillis() - start > 30_000L) break
                Thread.sleep(500L)
            }
            assertTrue(
                "the job must reach RUNNING before the host kills the main app: $state",
                state == com.helix.runtime.proot.ipc.ProotJobState.RUNNING,
            )
            // The host-readable marker (same-uid filesDir): the script parses it
            // to feed the post-death reconciliation phase. (System.out does not
            // cross the instrumentation stream on this platform.)
            File(context.filesDir, "LIFECYCLE-JOB-ID").writeText(jobId)
            Unit
        }
    }

    /**
     * Host-bracketed phase: the acceptance script schedules the companion
     * kill (`su 0 pkill -9`) ~6 s after instrumentation starts and marks the
     * run with `-e companion_kill 1`. The client must settle in a STABLE,
     * non-success, job-id-reconciled outcome — never a crash and never a
     * silent success:
     *  - `Unavailable(DEAD_OBJECT)` when the binder dies under a held
     *    transaction, or
     *  - `Terminal(ORPHANED)` when the next poll re-opens the connection
     *    (the designed HXA-083 cold-rebind recovery) and the fresh companion
     *    sweep reconciles the job by pid+starttime.
     * Standalone (no marker) it is assumed off: an app process cannot kill
     * another uid's companion on its own.
     */
    @Test
    fun phaseAwaitJobWhileTheHostKillsTheCompanion() {
        org.junit.Assume.assumeTrue(
            "host-bracketed phase: run via scripts/accept-hxa-086-lifecycle.sh (companion kill is scheduled there)",
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("companion_kill") == "1",
        )
        runOnWorker(timeoutMs = 180_000L) {
            markRan("e2e-lc-ran-await")
            val (spec, specArchive) = sleepSpec(90L)
            val (inputPfd, outputPfd) = pfdPair(specArchive)
            val submit = jobClient.submit(spec, inputPfd, outputPfd)
            val accepted =
                submit as? com.helix.runtime.proot.client.ProotJobClient.SubmitOutcome.Accepted
                    ?: error("the job must be accepted, got: $submit")
            val jobId = accepted.record.jobId
            File(context.filesDir, "LIFECYCLE-JOB-ID").writeText(jobId)
            val outcome = jobClient.awaitTerminal(jobId, pollIntervalMs = 500L, timeoutMs = 120_000L)
            when (outcome) {
                is com.helix.runtime.proot.client.ProotJobClient.AwaitOutcome.Unavailable -> {
                    assertEquals(
                        com.helix.runtime.proot.ipc.UnavailableCause.DEAD_OBJECT,
                        outcome.cause,
                    )
                }

                is com.helix.runtime.proot.client.ProotJobClient.AwaitOutcome.Terminal -> {
                    assertEquals(
                        "a mid-job companion death must reconcile ORPHANED, got: ${outcome.record.state}",
                        com.helix.runtime.proot.ipc.ProotJobState.ORPHANED,
                        outcome.record.state,
                    )
                }

                else -> {
                    error(
                        "the host kill must surface as a stable non-success outcome, got: $outcome",
                    )
                }
            }
            Unit
        }
    }

    /**
     * The reconciliation phase: after the host killed main app and/or the
     * companion mid-job and the companion was re-warmed (user repair entry),
     * the job record must have settled ORPHANED by job id — the journal is
     * the only truth, nothing is replayed.
     */
    @Test
    fun phaseQueryTheOrphanedJobByJobId() {
        val jobId =
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("job_id")
        org.junit.Assume.assumeTrue(
            "host-bracketed phase: the script passes the orphaned job id as the `job_id` argument",
            jobId != null,
        )
        markRan("e2e-lc-ran-orphan")
        runOnWorker(timeoutMs = 60_000L) {
            val start = System.currentTimeMillis()
            var record: com.helix.runtime.proot.ipc.ProotJobRecord? = null
            while (System.currentTimeMillis() - start < 45_000L) {
                val q = jobClient.query(jobId!!)
                if (q is com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome.Ok) {
                    record = q.record
                    if (q.record.state.isTerminal) break
                }
                Thread.sleep(1_000L)
            }
            assertNotNull("the job must be queryable by id after the re-warm: $record", record)
            val state = record!!.state
            assertTrue(
                "a mid-job kill must settle ORPHANED (or CANCELLED by the stop path), got: $state",
                state == com.helix.runtime.proot.ipc.ProotJobState.ORPHANED ||
                    state == com.helix.runtime.proot.ipc.ProotJobState.CANCELLED,
            )
            if (state == com.helix.runtime.proot.ipc.ProotJobState.ORPHANED) {
                assertEquals("ORPHANED never carries an exit code", null, record!!.exitCode)
            }
            Unit
        }
    }

    /** A 12 s job awaited to SUCCEEDED: the wake-lock sampler samples during it. */
    @Test
    fun phaseLongJobForWakelockSampling() {
        runOnWorker(timeoutMs = 120_000L) {
            val store = e2eWorkspaceStore()
            markRan("e2e-lc-ran-wl")
            val args =
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonArray {
                            add(JsonPrimitive("/bin/sh"))
                            add(JsonPrimitive("-c"))
                            add(JsonPrimitive("sleep 12 && echo WAKELOCK-SAMPLE-OK"))
                        },
                    )
                    put("timeoutSeconds", JsonPrimitive(60))
                }
            val completed =
                LinuxRunTool.executor(productionExecutor(store)).execute(linuxCall("tc-lc-wl-", args))
                    as ToolExecutorResult.Completed
            assertEquals(
                "SUCCEEDED",
                completed.output.jsonObject["state"]!!
                    .jsonPrimitive.content,
            )
            Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sleepSpec(secs: Long): Pair<com.helix.runtime.proot.ipc.ProotJobSpec, File> {
        val cacheDir = File(context.filesDir, "lc-spec-" + nextId()).apply { mkdirs() }
        scratch += cacheDir
        val manifest =
            com.helix.runtime.proot.core.JobManifestCodec.encode(
                com.helix.runtime.proot.core
                    .JobManifest(emptyList()),
            )
        val archive = File(cacheDir, "input.zip")
        com.helix.runtime.proot.core.JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(manifest)
        }
        // The wire field is the canonical MANIFEST-DOCUMENT hash (the
        // manifest.json bytes), exactly like the production tool computes it.
        val inputSha =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(manifest.encodeToByteArray())
                .joinToString("") { b -> "%02x".format(b) }
        return com.helix.runtime.proot.ipc.ProotJobSpec(
            executionId = "exec-lc-" + System.nanoTime().toUInt().toString(16),
            jobId =
                "job_" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(12)
                        .lowercase(),
            command =
                com.helix.runtime.proot.ipc.ProotJobCommand
                    .Argv(listOf("/bin/sh", "-c", "sleep $secs")),
            relativeWorkingDirectory = "",
            environment = emptyMap(),
            deadlineMs = (secs + 30) * 1000L,
            maxOutputBytes = 1_048_576L,
            inputManifestSha256 = inputSha,
        ) to archive
    }

    private fun pfdPair(archive: File): Pair<android.os.ParcelFileDescriptor, android.os.ParcelFileDescriptor> {
        val dir = File(context.filesDir, "lc-pfd-" + nextId()).apply { mkdirs() }
        scratch += dir
        val inputPfd = android.os.ParcelFileDescriptor.open(archive, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val outputPfd =
            android.os.ParcelFileDescriptor.open(
                File(dir, "output.zip"),
                android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        return inputPfd to outputPfd
    }

    private fun linuxCall(
        prefix: String,
        args: kotlinx.serialization.json.JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = prefix + nextId(),
            toolName = LinuxRunTool.NAME,
            toolVersion = "1",
            args = args,
            executionTarget = com.helix.core.model.ExecutionTargetType.LOCAL_PROOT,
            deadline = Instant.now().plusSeconds(170),
            cancel =
                object : CancelSignal {
                    override fun isCancelled(): Boolean = false
                },
        )

    private fun productionExecutor(
        store: WorkspaceArtifactStore,
        gate: () -> LinuxRuntimeGate = { LinuxRuntimeGate.READY },
    ): LinuxRunTool.LinuxExecutor {
        val scratchRoot = File(context.filesDir, "proot-lc-e2e-" + nextId())
        scratch += scratchRoot
        return LinuxRunTool.ProductionLinuxExecutor(
            client = jobClient,
            gate = gate,
            store = store,
            scratchRoot = scratchRoot,
            jobIdProvider = {
                "job_" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(12)
                        .lowercase()
            },
            knownSecretValues = { emptySet() },
        )
    }

    /** The gate the module uses: local state + the persisted anchor (no bind). */
    private fun supervisorGate(): LinuxRuntimeGate {
        val cause = supervisor.checkLocalState()
        return when {
            cause != null -> {
                when (cause) {
                    com.helix.runtime.proot.ipc.UnavailableCause.NOT_INSTALLED -> {
                        LinuxRuntimeGate.NOT_INSTALLED
                    }

                    else -> {
                        LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED
                    }
                }
            }

            supervisor.anchorPresent() -> {
                LinuxRuntimeGate.READY
            }

            else -> {
                LinuxRuntimeGate.NOT_VERIFIED
            }
        }
    }

    private fun e2eWorkspaceStore(): WorkspaceArtifactStore {
        val root = context.filesDir
        val rootPath =
            java.nio.file.Paths
                .get(root.absolutePath)
        java.nio.file.Files
            .createDirectories(rootPath)
        val resolver = { sid: String ->
            require(sid == "app") { "scope $sid unavailable in the E2E" }
            rootPath
        }
        return WorkspaceArtifactStore(resolver)
    }

    /** Fresh host-side marker (test process filesDir): proof the test body ran. */
    private var ranMarker: File? = null

    private fun markRan(tag: String) {
        val m = File(context.filesDir, tag)
        m.writeText("ran at " + System.currentTimeMillis())
        ranMarker = m
    }

    private fun nextId(): String = counter.incrementAndGet().toString()

    private fun runOnWorker(
        timeoutMs: Long = 120_000L,
        block: () -> Unit,
    ) {
        val future = CompletableFuture.runAsync { block() }
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Suppress("SwallowedException") // a missing companion package is the expected absent state
    private fun companionAppInfo(ctx: Context): ApplicationInfo? =
        try {
            ctx.packageManager
                .getPackageInfo(
                    com.helix.runtime.proot.ipc.ProotRuntimeProtocol.RUNTIME_PACKAGE,
                    0,
                ).applicationInfo
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun companionInstalled(ctx: Context): Boolean = companionAppInfo(ctx) != null

    private fun probeStoppedState(ctx: Context): Boolean {
        val appInfo = companionAppInfo(ctx) ?: return false
        return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
    }
}
