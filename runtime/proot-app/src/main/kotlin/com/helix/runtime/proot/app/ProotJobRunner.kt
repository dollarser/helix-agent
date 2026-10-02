package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobExecutionWindow
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobHandler
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRefusal
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The companion's job execution engine (HXA-084; architecture doc sections 6.5/6.7).
 *
 * Execution model:
 * - Up to four job workers run independent lifecycles (extract -> launch -> wait ->
 *   output archive -> terminal record). Physical capacity survives cancellation until exit;
 *   journal transactions and per-job identity remain synchronized, not user result paths.
 * - ONE watchdog thread enforces the hard deadline: past the monotonic admission
 *   window the job's process GROUP is killed (the job launches under the
 *   host `/system/bin/setsid`, so its pgid equals its pid — verified on device)
 *   and the job ends TIMED_OUT.
 * - Cancel is the same group kill ending in CANCELLED; the main app never
 *   replays a cancelled job (ADR-0007: Binder loss reconciles by job id).
 * - Orphan sweep at service (re)start: a PENDING/RUNNING record from a previous
 *   process incarnation is terminal ORPHANED, and its process group is killed
 *   ONLY when /proc proves the pid still holds the SAME process (matching
 *   starttime ticks) — a reused pid is never touched.
 *
 * Output: after the process exits, the workspace artifacts plus the capped
 * stdout/stderr are archived by [JobZipWriter] (manifest entry first) into the
 * CLIENT-PROVIDED output write PFD. The client re-opens its file, extracts with
 * [ZipJobExtractor], and verifies the manifest hash against the record's
 * `outputManifestSha256` — that is the reconciliation proof.
 *
 * The runner owns lifecycle, watchdog and terminal publication in one process-wide
 * object. Bounded capture and archive encoding are independent helpers.
 */
@Suppress("TooManyFunctions", "LargeClass") // One owner for existing shared Runtime lifecycle maps.
class ProotJobRunner private constructor(
    private val context: Context,
) : ProotJobHandler,
    com.helix.runtime.proot.ipc.ProotJobResultHandler,
    com.helix.runtime.proot.ipc.ProotOwnedJobHandler,
    com.helix.runtime.proot.ipc.ProotLogHandler {
    companion object {
        const val MAX_MANUAL_TERMINALS = 2
        const val MAX_JOB_EXECUTIONS = 4
        const val MANUAL_TERMINAL_PREFIX = "pty-"
        private val holder = AtomicReference<ProotJobRunner>()

        /** Process-wide singleton: the service may rebind, but one process = one runner. */
        @Synchronized // Concurrent cold bindings must not construct competing runners and execution pools.
        fun get(context: Context): ProotJobRunner {
            val ctx = context.applicationContext
            holder.get()?.let { return it }
            val created = ProotJobRunner(ctx)
            holder.set(created)
            created.start()
            return created
        }
    }

    private val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
    internal val jobStore: ProotJobStore get() = store
    private val logs by lazy {
        runCatching {
            com.helix.runtime.proot.core
                .JobLogSpool(File(context.cacheDir, "job-log-preview"))
        }.onFailure { android.util.Log.w("ProotJobRunner", "Log preview unavailable", it) }
            .getOrNull()
    }

    override fun readLog(
        jobId: String,
        inputManifestSha256: String,
        cursor: String?,
    ): com.helix.runtime.proot.core.JobLogPage? {
        val record = store.load(jobId) ?: return null
        require(record.inputManifestSha256 == inputManifestSha256) { "Log binding mismatch" }
        return logs?.read(jobId, cursor)
    }

    private val jobExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            MAX_JOB_EXECUTIONS,
            MAX_JOB_EXECUTIONS,
            30L,
            TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            { r -> Thread(r, "proot-job").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor
                .AbortPolicy(),
        )
    private val capacity =
        com.helix.runtime.proot.core
            .RuntimeExecutionCapacity()

    @Volatile private var recoveryReady = false
    private val recoveryFinished = java.util.concurrent.CountDownLatch(1)

    private fun ready(): Boolean =
        try {
            recoveryReady || (recoveryFinished.await(2, TimeUnit.SECONDS) && recoveryReady)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private val watchdogExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "proot-watchdog").apply { isDaemon = true }
        }

    /** jobId -> the live process handle of a RUNNING job of THIS process. */
    private val liveJobs = ConcurrentHashMap<String, LiveJob>()
    private val processOwners = ConcurrentHashMap<String, ProotProcessOwner>()
    private val cancellationFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val owners = ProotJobOwners()
    private val executionWindows = ConcurrentHashMap<String, JobExecutionWindow>()
    private val expiredLeases = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    internal fun reserveDetached(jobId: String): Boolean {
        if (jobId.startsWith(MANUAL_TERMINAL_PREFIX)) {
            return reserveManualTerminal(jobId.removePrefix(MANUAL_TERMINAL_PREFIX))
        }
        return ready() && store.pruneAndBudgetAvailable(System.currentTimeMillis()) && capacity.reserveJob(jobId)
    }

    @Synchronized
    internal fun releaseDetached(jobId: String) {
        if (jobId.startsWith(MANUAL_TERMINAL_PREFIX)) {
            releaseManualTerminal(jobId.removePrefix(MANUAL_TERMINAL_PREFIX))
            return
        }
        capacity.releaseReservation(jobId)
    }

    @Synchronized
    internal fun reserveManualTerminal(sessionId: String): Boolean = ready() && capacity.reserveTerminal(sessionId)

    @Synchronized
    internal fun releaseManualTerminal(sessionId: String) {
        capacity.releaseTerminal(sessionId)
    }

    internal fun isJobRunning(id: String): Boolean = capacity.isJobRunning(id)

    internal fun reserveMaintenance(id: String): Boolean = ready() && capacity.reserveMaintenance(id)

    internal fun releaseMaintenance(id: String) = capacity.releaseMaintenance(id)

    @Synchronized
    internal fun expireDetached(jobId: String) {
        if (store.load(jobId)?.state?.isTerminal != false) return
        expiredLeases.add(jobId)
        liveJobs[jobId]?.let { killProcessGroup(it.pid) }
    }

    internal fun submitDetached(
        spec: ProotJobSpec,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
    ): ProotJobSubmitResult = submitWithOwner(spec, input, output, null, detached = true)

    private class LiveJob(
        val pid: Int,
        val process: Process,
        val cancelRequested: AtomicBoolean,
        val deadlineHit: AtomicBoolean,
        val outputLimitHit: AtomicBoolean,
        val executionWindow: JobExecutionWindow,
        val watchdog: ScheduledFuture<*>,
    )

    @Suppress("TooGenericExceptionCaught") // Recovery failure stays visible and never opens execution admission.
    private fun start() {
        // Orphan sweep first: previous-incarnation jobs can never be continued.
        watchdogExecutor.execute {
            try {
                sweepOrphans()
                recoveryReady = true
            } catch (failure: Exception) {
                android.util.Log.e("ProotJobRunner", "Runtime identity recovery failed", failure)
            } finally {
                recoveryFinished.countDown()
            }
        }
        watchdogExecutor.scheduleWithFixedDelay(
            {
                enforceDeadlinesQuiet()
            },
            500L,
            500L,
            TimeUnit.MILLISECONDS,
        )
    }

    // ------------------------------------------------------------------ submit

    /**
     * The fast path (binder thread): duplicate + budget checks, then the PENDING
     * record is written and the lifecycle runs on the job thread. The PFDs are
     * owned by the runner from this point (closed in every outcome).
     */
    override fun submit(
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
    ): ProotJobSubmitResult = submitWithOwner(spec, inputPfd, outputPfd, null)

    @Synchronized
    private fun submitWithOwner(
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
        owner: android.os.IBinder?,
        detached: Boolean = false,
    ): ProotJobSubmitResult {
        var handedOff = false
        return try {
            submitChecked(spec, inputPfd, outputPfd, owner, detached).also {
                handedOff = it is ProotJobSubmitResult.Accepted
            }
        } finally {
            if (!handedOff) {
                try {
                    inputPfd.close()
                } finally {
                    outputPfd.close()
                }
            }
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Release unsubmitted capacity, then rethrow the cause.
    private fun submitChecked(
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
        owner: android.os.IBinder?,
        detached: Boolean = false,
    ): ProotJobSubmitResult {
        val window = JobExecutionWindow(SystemClock.elapsedRealtime(), spec.deadlineMs)
        val now = System.currentTimeMillis()
        val entries = store.entries()
        val existing = entries[spec.jobId]
        if (existing != null) {
            return if (existing.executionId == spec.executionId &&
                existing.inputManifestSha256 == spec.inputManifestSha256
            ) {
                ProotJobSubmitResult.Duplicate(existing)
            } else {
                ProotJobSubmitResult.Rejected(ProotJobRefusal.INVALID_SPEC)
            }
        }
        // The same executionId under a different jobId is the same job: never
        // start twice, return the existing record.
        val sameExecution = entries.values.firstOrNull { it.executionId == spec.executionId }
        if (sameExecution != null) {
            return if (sameExecution.inputManifestSha256 == spec.inputManifestSha256) {
                ProotJobSubmitResult.Duplicate(sameExecution)
            } else {
                ProotJobSubmitResult.Rejected(ProotJobRefusal.INVALID_SPEC)
            }
        }
        if (!store.pruneAndBudgetAvailable(now)) {
            return ProotJobSubmitResult.Rejected(ProotJobRefusal.JOURNAL_FULL)
        }
        if (!ready() || !capacity.beginJob(spec.jobId, detached)) {
            return ProotJobSubmitResult.Rejected(ProotJobRefusal.EXECUTION_BUSY)
        }
        val pending =
            ProotJobRecord(
                jobId = spec.jobId,
                executionId = spec.executionId,
                inputManifestSha256 = spec.inputManifestSha256,
                state = ProotJobState.PENDING,
                createdAtEpochMs = now,
            )
        return try {
            store.put(pending)
            executionWindows[spec.jobId] = window
            cancellationFlags.putIfAbsent(spec.jobId, AtomicBoolean(false))
            if (owner != null) owners.watch(spec.jobId, owner) { cancel(spec.jobId) }
            jobExecutor.execute { runJob(spec, pending, inputPfd, outputPfd, window) }
            ProotJobSubmitResult.Accepted(pending)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            owners.release(spec.jobId)
            executionWindows.remove(spec.jobId)
            cancellationFlags.remove(spec.jobId)
            capacity.finishJob(spec.jobId)
            store.put(pending.copy(state = ProotJobState.FAILED, terminalAtEpochMs = System.currentTimeMillis()))
            ProotJobSubmitResult.Rejected(ProotJobRefusal.EXECUTION_BUSY)
        } catch (failure: Exception) {
            owners.release(spec.jobId)
            executionWindows.remove(spec.jobId)
            cancellationFlags.remove(spec.jobId)
            capacity.finishJob(spec.jobId)
            throw failure
        }
    }

    override fun submitOwned(
        owner: android.os.IBinder,
        spec: ProotJobSpec,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
    ): ProotJobSubmitResult = submitWithOwner(spec, input, output, owner)

    // ------------------------------------------------------------------ lifecycle

    // Every failure is a terminal STATE, never a crash; the launch sequence is
    // one strict state machine (extract -> verify -> launch -> capture -> terminal).
    @Suppress(
        "TooGenericExceptionCaught",
        "SwallowedException",
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ReturnCount",
    )
    private fun runJob(
        spec: ProotJobSpec,
        pending: ProotJobRecord,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
        executionWindow: JobExecutionWindow,
    ) {
        val cancelRequested = cancellationFlags.getValue(spec.jobId)
        var log: com.helix.runtime.proot.core.JobLogSpool.Sink? = null
        var live: LiveJob? = null
        val processOwner = ProotProcessOwner(::killProcessGroup)
        processOwners[spec.jobId] = processOwner
        try {
            log = logs?.open(spec.jobId)
            // 1) Extract + re-verify the input archive (untrusted bytes: central
            //    directory scan, path validation, per-entry manifest re-hash).
            val jobDir = store.jobDir(spec.jobId)
            val workspace = File(jobDir, "workspace")
            val inputArchive = File(jobDir, "input.zip")
            ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { input ->
                FileOutputStream(inputArchive).use { out ->
                    copyJobInput(input, out) {
                        cancelRequested.get() ||
                            executionWindow.remainingMs(SystemClock.elapsedRealtime()) == 0L
                    }
                }
            }
            val extraction =
                try {
                    ZipJobExtractor.extract(inputArchive, workspace)
                } catch (e: JobArchiveException) {
                    terminalInputInvalid(pending, outputPfd)
                    return
                }
            if (extraction.manifestSha256 != spec.inputManifestSha256) {
                terminalInputInvalid(pending, outputPfd)
                return
            }
            val stdinFile = spec.stdinRelativePath?.let { File(workspace, it) }
            if (stdinFile != null && !stdinFile.isFile) {
                terminalInputInvalid(pending, outputPfd)
                return
            }
            inputArchive.delete()

            if (stopBeforeLaunch(pending, outputPfd, cancelRequested, executionWindow)) return

            // 2) The runtime must be active (installed + activated, HXA-082).
            val installId =
                RootFsInstaller.currentActive(ProotRuntimeInstaller.runtimeRoot(context))?.installId
            if (installId == null) {
                terminalFailed(pending, outputPfd, null, "no active runtime install")
                return
            }
            store.put(pending.copy(state = ProotJobState.RUNNING))

            // 3) Launch under the host setsid (new session => pgid == pid => the
            //    group kill below can be aimed precisely at the job).
            val installDir = File(ProotRuntimeInstaller.runtimeRoot(context), installId)
            val jobTmp = File(jobDir, "tmp").apply { mkdirs() }
            val media = ProotGuestMedia.prepare(context, jobTmp)
            val commandArgs =
                when (val command = spec.command) {
                    is ProotJobCommand.Argv -> command.arguments

                    // The script is ONE argv element — shell syntax was explicitly
                    // requested by the caller; nothing is assembled into an
                    // unescaped command string.
                    is ProotJobCommand.Script -> listOf("/bin/sh", "-c", command.script)
                }
            val prootArgs =
                listOf(
                    File(installDir, "bin/proot").absolutePath,
                    "-r",
                    File(installDir, "rootfs").absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "${jobTmp.absolutePath}:/tmp",
                    "-b",
                    "${workspace.absolutePath}:/workspace",
                ) + media.bindings +
                    listOf(
                        "-w",
                        if (spec.relativeWorkingDirectory.isEmpty()) {
                            "/workspace"
                        } else {
                            "/workspace/${spec.relativeWorkingDirectory}"
                        },
                    ) + commandArgs
            // LAUNCH CHAIN (device-verified, HXA-084):
            //   setsid  -> the job gets its own session (pgid == pid, group kill)
            //   linker64 -> SELinux gives the app domain execute_no_trans for
            //               /system files but NOT for app_data_file: execve'ing
            //               our own proot binary directly is denied
            //               (avc: denied { execute_no_trans } for bin/proot).
            //               The system linker IS executable; it MAPS the target
            //               (plain `execute` on app_data_file is granted), which
            //               is exactly the termux-exec `system_linker_exec`
            //               workaround for the app-data-file execute restriction.
            //               64-bit linker: the shipped ABIs (arm64-v8a/x86_64)
            //               are 64-bit only, and minSdk 29 runs them as 64-bit.
            val builder =
                ProcessBuilder(listOf("/system/bin/setsid", "/system/bin/linker64") + prootArgs)
                    .directory(jobDir)
            if (stdinFile != null) builder.redirectInput(stdinFile)
            builder.environment().clear()
            builder.environment().putAll(spec.environment)
            // Device-verified mandatory environment (HXA-084 probe): this Termux
            // build of PRoot cannot find its own libraries (no RPATH), insists on
            // the Termux loader path unless PROOT_LOADER overrides it, and refuses
            // to build its glue rootfs without PROOT_TMP_DIR. These are fixed,
            // non-secret paths; they are visible to the guest process too (envp is
            // shared) — documented, not hidden.
            builder.environment()["LD_LIBRARY_PATH"] = File(installDir, "bin/lib").absolutePath
            // PRoot rewrites the tracee's IN-FLIGHT execve to point at
            // $PROOT_LOADER (an LD_PRELOAD hook cannot fire for that: the
            // tracee never calls the execve() C function a second time).
            // The loader therefore must live where the app domain MAY exec:
            // the APK install dir (apk_data_file: execute + execute_no_trans
            // are granted). It ships as a regular native library
            // (jniLibs -> extracted at install) and is hash-checked against
            // the locked runtime loader on every job (single source of truth
            // stays the HXA-081 lock; the copy is APK-signature protected).
            val executableLoader = executableLoader(context, installDir)
            if (executableLoader == null) {
                terminalFailed(pending, outputPfd, null, "executable loader is unavailable: $loaderError")
                return
            }
            builder.environment()["PROOT_LOADER"] = executableLoader.absolutePath
            builder.environment()["PROOT_TMP_DIR"] = jobTmp.absolutePath
            if (builder.environment()["PATH"] == null) {
                builder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            }
            builder.environment()["PATH"] = media.path(builder.environment()["PATH"])
            builder.environment()["HELIX_FFMPEG_BRIDGE"] = if (media.available) "ready-av1-bionic" else "unavailable"

            if (stopBeforeLaunch(pending, outputPfd, cancelRequested, executionWindow)) return
            val deadlineHit = AtomicBoolean(false)
            val outputBudget = OutputBudget(spec.maxOutputBytes)
            val stderrBudget = StreamOutputBudget(outputBudget, spec.maxStderrBytes)
            val process =
                try {
                    processOwner.start { builder.start() }
                } catch (e: Exception) {
                    terminalFailed(pending, outputPfd, null, "launch failed: ${e.message?.take(120)}")
                    return
                }
            // Resolve the host pid before starting the stream pumps: an output cap
            // must be able to kill the process group immediately, including for a
            // long-lived stdio server that would otherwise block forever on a full pipe.
            val childPid = childPid(process).also { processOwner.pid = it }
            if (childPid == null) {
                process.destroyForcibly()
                terminalFailed(pending, outputPfd, null, "cannot identify the child pid")
                return
            }
            val stdout =
                BoundedCapture(
                    outputBudget,
                    { bytes, count -> log?.offer(1, bytes, count) },
                ) { killProcessGroup(childPid) }
            val stderr =
                BoundedCapture(
                    stderrBudget,
                    { bytes, count -> log?.offer(2, bytes, count) },
                ) { killProcessGroup(childPid) }
            val stdoutReader = Thread { process.inputStream.use { stdout.drain(it) } }
            val stderrReader = Thread { process.errorStream.use { stderr.drain(it) } }
            stdoutReader.start()
            stderrReader.start()

            // 4) The process identity is persisted for the orphan sweep (the
            //    starttime guard makes pid reuse harmless).
            persistProcessMeta(store, spec.jobId, childPid)

            live =
                LiveJob(
                    pid = childPid,
                    process = process,
                    cancelRequested = cancelRequested,
                    deadlineHit = deadlineHit,
                    outputLimitHit = outputBudget.hitLimit,
                    executionWindow = executionWindow,
                    watchdog =
                        watchdogExecutor.scheduleWithFixedDelay(
                            {
                                if (executionWindow.remainingMs(SystemClock.elapsedRealtime()) == 0L) {
                                    deadlineHit.set(true)
                                    killProcessGroup(childPid)
                                }
                            },
                            250L,
                            250L,
                            TimeUnit.MILLISECONDS,
                        ),
                )
            liveJobs[spec.jobId] = live
            if (cancelRequested.get() || spec.jobId in expiredLeases) killProcessGroup(childPid)
            // The 通知停止 surface (HXA-086): a plain (non-FGS) notification with a
            // stop action for the lifetime of the RUNNING state.
            ProotJobNotification.postRunning(context, spec.jobId)

            // 5) Wait (bounded: the watchdog's group kill is the primary deadline
            //    enforcement; the +30s margin covers a stuck kill).
            var exited =
                process.waitFor(
                    executionWindow.remainingMs(SystemClock.elapsedRealtime()) + 30_000L,
                    TimeUnit.MILLISECONDS,
                )
            if (!exited) {
                deadlineHit.set(true)
                killProcessGroup(childPid)
                exited = process.waitFor(10_000L, TimeUnit.MILLISECONDS)
            }
            if (!exited) {
                process.destroyForcibly()
                killProcessGroup(childPid)
                exited = process.waitFor(10_000L, TimeUnit.MILLISECONDS)
            }
            if (!exited) {
                // The process table itself gave up: treat as timed out.
                deadlineHit.set(true)
                stdoutReader.join(5_000L)
                stderrReader.join(5_000L)
                stdout.finish()
                stderr.finish()
                log?.finish(true)
                terminal(pending, outputPfd, ProotJobState.TIMED_OUT, null, stdout, stderr)
                return
            }
            stdoutReader.join(5_000L)
            stderrReader.join(5_000L)
            stdout.finish()
            stderr.finish()
            log?.finish(stdout.truncated || stderr.truncated || !stdout.complete || !stderr.complete)
            // A child may retain the pipe after its parent exits. Never publish a successful
            // truncated snapshot while a pump is still waiting; stop the remaining group.
            if (stdoutReader.isAlive || stderrReader.isAlive) killProcessGroup(childPid)

            // 6) Terminal state from the flags + the exit code.
            val state =
                when {
                    outputBudget.hitLimit.get() -> ProotJobState.OUTPUT_LIMIT_EXCEEDED
                    cancelRequested.get() -> ProotJobState.CANCELLED
                    deadlineHit.get() -> ProotJobState.TIMED_OUT
                    !stdout.complete || !stderr.complete -> ProotJobState.FAILED
                    process.exitValue() == 0 -> ProotJobState.SUCCEEDED
                    else -> ProotJobState.FAILED
                }
            terminal(pending, outputPfd, state, process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            log?.finish(true)
            // An unexpected lifecycle failure is a terminal FAILED with the process
            // exit when there was one — never a crash of the companion.
            val exit = processOwner.exitCode()
            terminalFailed(pending, outputPfd, exit, "lifecycle failure: ${e.message?.take(120)}")
        } finally {
            // A terminal UNKNOWN/ORPHANED record is not permission to overlap a still-live process.
            processOwner.awaitExit()
            try {
                log?.finish()
            } finally {
                live?.watchdog?.cancel(false)
                processOwners.remove(spec.jobId, processOwner)
                liveJobs.remove(spec.jobId)
                cancellationFlags.remove(spec.jobId, cancelRequested)
                expiredLeases.remove(spec.jobId)
                executionWindows.remove(spec.jobId)
                try {
                    inputPfd.close()
                } finally {
                    try {
                        outputPfd.close()
                    } finally {
                        owners.release(spec.jobId)
                        capacity.finishJob(spec.jobId)
                    }
                }
            }
        }
    }

    @Suppress("SwallowedException") // the PFD must close in EVERY outcome

    private fun terminalInputInvalid(
        pending: ProotJobRecord,
        outputPfd: ParcelFileDescriptor,
    ) {
        outputPfd.close()
        ProotJobNotification.cancel(context, pending.jobId)
        store.deletePayload(pending.jobId)
        publishTerminal(
            pending.copy(
                state = ProotJobState.INPUT_INVALID,
                terminalAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /** User cancellation wins a simultaneous expiry; neither path launches a process. */
    private fun stopBeforeLaunch(
        pending: ProotJobRecord,
        outputPfd: ParcelFileDescriptor,
        cancelRequested: AtomicBoolean,
        window: JobExecutionWindow,
    ): Boolean {
        val cancelled = cancelRequested.get()
        val expired = pending.jobId in expiredLeases || window.remainingMs(SystemClock.elapsedRealtime()) == 0L
        if (!cancelled && !expired) return false
        outputPfd.close()
        ProotJobNotification.cancel(context, pending.jobId)
        publishTerminal(
            pending.copy(
                state = if (cancelled) ProotJobState.CANCELLED else ProotJobState.TIMED_OUT,
                terminalAtEpochMs = System.currentTimeMillis(),
            ),
        )
        return true
    }

    @Suppress("SwallowedException") // the PFD must close in EVERY outcome

    private fun terminalFailed(
        pending: ProotJobRecord,
        outputPfd: ParcelFileDescriptor,
        exitCode: Int?,
        note: String,
    ) {
        outputPfd.close()
        ProotJobNotification.cancel(context, pending.jobId)
        publishTerminal(
            pending.copy(
                state = ProotJobState.FAILED,
                terminalAtEpochMs = System.currentTimeMillis(),
                exitCode = exitCode,
            ),
        )
        File(store.jobDir(pending.jobId), "failure.txt").writeText(note)
    }

    /**
     * The terminal path WITH the output archive: workspace artifacts + capped
     * streams are written (manifest first) into the client's output PFD. A
     * SUCCEEDED whose artifact could not be delivered is demoted to FAILED — the
     * record simply has no outputManifestSha256 to reconcile against.
     */
    @Suppress("SwallowedException") // the PFD must close in EVERY outcome

    private fun terminal(
        pending: ProotJobRecord,
        outputPfd: ParcelFileDescriptor,
        state: ProotJobState,
        exitCode: Int?,
        stdout: BoundedCapture,
        stderr: BoundedCapture,
    ) {
        val now = System.currentTimeMillis()
        val jobDir = store.jobDir(pending.jobId)
        var outputManifest: String? = null
        var finalState = state

        // Archive construction/persistence failure demotes success to FAILED.
        // A failed initial transfer preserves the durable result for explicit recovery.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val manifestDocument =
            try {
                val stdoutFile = File(jobDir, "_stdout.txt").apply { writeBytes(stdout.bytes) }
                val stderrFile = File(jobDir, "_stderr.txt").apply { writeBytes(stderr.bytes) }
                ProotOutputDelivery
                    .persistAndDeliver(store.outputFile(pending.jobId), outputPfd) { archive ->
                        buildOutputArchive(
                            archive,
                            File(jobDir, "workspace"),
                            "stdout.txt" to stdoutFile,
                            "stderr.txt" to stderrFile,
                        )
                    }.manifestDocument
                // No durable archive: the failure note records missing output evidence.
            } catch (e: Exception) {
                null
            }
        if (manifestDocument != null) {
            outputManifest = sha256Of(manifestDocument.toByteArray())
        } else if (finalState == ProotJobState.SUCCEEDED) {
            finalState = ProotJobState.FAILED
        }
        // Terminal evidence for the operator and for the acceptance suites: the
        // state, the exit code when there was one, the delivery failure when the
        // archive could not be built, and a tail of the captured stderr.
        if (finalState != ProotJobState.SUCCEEDED) {
            val stderrTail =
                String(stderr.bytes, Charsets.UTF_8).replace("\n", " | ").takeLast(1024)
            val note =
                buildString {
                    append("state=").append(finalState)
                    if (exitCode != null) append(" exit=").append(exitCode)
                    if (manifestDocument == null) append(" outputDelivery=failed")
                    if (stderrTail.isNotBlank()) append(" stderr:").append(stderrTail)
                }
            File(jobDir, "failure.txt").writeText(note)
        }
        // Already closed by AutoCloseOutputStream in the normal path.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            outputPfd.close()
        } catch (e: Exception) {
            // already closed by AutoCloseOutputStream
        }
        ProotJobNotification.cancel(context, pending.jobId)
        publishTerminal(
            pending.copy(
                state = finalState,
                terminalAtEpochMs = now,
                exitCode = exitCode,
                stdoutBytes = stdout.bytes.size.toLong(),
                stderrBytes = stderr.bytes.size.toLong(),
                truncated = stdout.truncated || stderr.truncated,
                outputManifestSha256 = outputManifest,
            ),
        )
    }

    // ------------------------------------------------------------------ control

    override fun acknowledgeResult(
        jobId: String,
        terminalCommit: String,
        now: Long,
    ) = store.acknowledge(jobId, terminalCommit, now)

    override fun fetchResult(jobId: String) = ProotResultArchiveStore(store).open(jobId)

    override fun query(jobId: String): ProotJobRecord? = store.load(jobId)

    @Synchronized
    @Suppress("ReturnCount")
    override fun cancel(jobId: String): ProotJobRecord? {
        val record = store.load(jobId) ?: return null
        if (record.state.isTerminal) return record
        cancellationFlags.computeIfAbsent(jobId) { AtomicBoolean(false) }.set(true)
        val live = liveJobs[jobId]
        if (live != null) {
            live.cancelRequested.set(true)
            killProcessGroup(live.pid)
        }
        // A PENDING job (not yet launched) is cancelled via the flag the job
        // thread checks between phases and ends CANCELLED.
        return store.load(jobId)
    }

    /** Cancellation and terminal publication share a linearization point, including archive time. */
    @Synchronized
    private fun publishTerminal(record: ProotJobRecord) {
        if (store.load(record.jobId)?.state?.isTerminal == true) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = executionWindows[record.jobId]?.elapsedMs(now)
        val physical = ProotExecutionExit.terminalState(record.state, processOwners[record.jobId]?.isAlive() == true)
        store.put(
            record
                .copy(state = physical)
                .copy(
                    elapsedDurationMs = elapsed,
                    terminalElapsedMs = now.takeIf { elapsed != null },
                ).withStopReason(
                    cancelled = cancellationFlags[record.jobId]?.get() == true,
                    leaseExpired = record.jobId in expiredLeases,
                ),
        )
    }

    @Suppress("ReturnCount")
    override fun reconcile(
        jobId: String,
        reconciledAtEpochMs: Long,
    ): ProotJobRecord? {
        val record = store.load(jobId) ?: return null
        if (!record.state.isTerminal) return record
        store.reconcile(jobId, reconciledAtEpochMs)
        return store.load(jobId)
    }

    /**
     * Watchdog tick: past-deadline live jobs get their group killed. A dead
     * watchdog is a missed deadline; the job thread's bounded waitFor covers it.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun enforceDeadlinesQuiet() {
        try {
            enforceDeadlines()
        } catch (e: Exception) {
            // The watchdog must never die; the fallback waitFor covers a missed tick.
        }
    }

    private fun enforceDeadlines() {
        val now = SystemClock.elapsedRealtime()
        liveJobs.forEach { (_, live) ->
            if (live.executionWindow.remainingMs(now) == 0L) {
                live.deadlineHit.set(true)
                killProcessGroup(live.pid)
            }
        }
    }

    fun sweepOrphans() = sweepProotOrphans(store, ::killProcessGroup)
}

@Suppress("TooGenericExceptionCaught") // a failing check is a skipped tick, not a crash
@Volatile
private var persistMetaError: String? = null

/**
 * Kills the job's process GROUP (setsid made pgid == pid), then sweeps the
 * /proc descendant tree (a grandchild that re-parented mid-kill must not
 * outlive the job).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // kill failures are retried by the tree sweep
private fun killProcessGroup(pid: Int) {
    try {
        Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid")).waitFor(2_000L, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        // No group (or already dead): the tree pass below still cleans up.
    }
    repeat(2) {
        val descendants = processTree(pid)
        if (descendants.isEmpty()) return
        descendants.forEach { d ->
            try {
                Runtime
                    .getRuntime()
                    .exec(
                        arrayOf("kill", "-9", d.toString()),
                    ).waitFor(1_000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // gone already
            }
        }
        Thread.sleep(100L)
    }
}

/** Persists the process identity for the orphan sweep (`pid=` / `startTicks=` lines). */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun persistProcessMeta(
    store: ProotJobStore,
    jobId: String,
    pid: Int,
) {
    val startTicks =
        try {
            val stat = File("/proc/$pid/stat").readText()
            val closeParen = stat.lastIndexOf(')')
            val fields = stat.substring(closeParen + 2).trim().split(" ")
            // starttime is field 22 (index 19 after the state field at index 1).
            fields.getOrNull(19).orEmpty()
        } catch (e: Exception) {
            // no readable stat: no starttime proof, so the sweep will not kill
            ""
        }
    try {
        File(store.jobDir(jobId), "proc.txt").writeText("pid=$pid\nstartTicks=$startTicks\n")
    } catch (e: Exception) {
        // Without the meta the sweep cannot prove ownership: it still ends the
        // job ORPHANED, it simply never kills.
        persistMetaError = "process meta write failed: ${e.javaClass.simpleName}"
    }
}

private fun ProotJobRecord.withStopReason(
    cancelled: Boolean,
    leaseExpired: Boolean,
): ProotJobRecord =
    copy(
        state =
            when {
                state == ProotJobState.ORPHANED -> state
                cancelled -> ProotJobState.CANCELLED
                leaseExpired -> ProotJobState.TIMED_OUT
                else -> state
            },
        terminalAtEpochMs = maxOf(createdAtEpochMs, requireNotNull(terminalAtEpochMs)),
    )

private fun sha256Of(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * The child's host pid. `android.jar` exposes NO `Process.pid()` at any API
 * level (at API 36 `java.lang.Process` is an abstract marker class), so the
 * private `pid` field is read reflectively from the CONCRETE runtime class
 * (libcore keeps it on the implementation subclass; walking the hierarchy
 * survives any further reshuffle). java.lang.Process* is not on the
 * reflection blocklist. A null result is a platform fault: the caller fails
 * the job closed (it cannot kill precisely what it cannot identify).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
private fun childPid(process: Process): Int? {
    var clazz: Class<*>? = process.javaClass
    while (clazz != null) {
        try {
            val field = clazz.getDeclaredField("pid")
            field.isAccessible = true
            return field.getInt(process)
        } catch (e: NoSuchFieldException) {
            clazz = clazz.superclass
        } catch (e: Exception) {
            return null
        }
    }
    return null
}

/**
 * The PRoot loader where the tracee's rewritten execve CAN succeed.
 *
 * Android SELinux denies the app domain execute_no_trans on app_data_file
 * (device-verified: avc denial on bin/proot and on the loader alike), but
 * grants it on apk_data_file — the APK install dir. The loader therefore
 * ships as a native library (extracted at install with extractNativeLibs)
 * and [PROOT_LOADER] points at that copy. Integrity: the copy must hash to
 * the SAME bytes as the HXA-081 lock-verified loader of the active runtime
 * install, or the job fails closed (one truth: the lock; the copy is an
 * exec-able replica, APK-signature protected).
 */
@Volatile
private var loaderCache: Pair<File, Long>? = null

@Volatile
private var loaderError: String = "unknown"

// A failed check is a clean job refusal, not a crash; one return per
// distinct refusal.
@Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
private fun executableLoader(
    context: Context,
    installDir: File,
): File? {
    loaderCache?.let { (file, verifiedForInstall) ->
        if (file.isFile && verifiedForInstall == installDir.canonicalFile.hashCode().toLong()) return file
    }
    val candidate = File(context.applicationInfo.nativeLibraryDir, "libhelix_loader.so")
    val locked = File(installDir, "bin/loader")
    return try {
        if (!candidate.isFile) {
            loaderError = "loader not extracted: ${candidate.absolutePath}"
            return null
        }
        if (!locked.isFile) {
            loaderError = "locked loader missing: ${locked.absolutePath}"
            return null
        }
        if (sha256OfFile(candidate) != sha256OfFile(locked)) {
            loaderError = "loader hash mismatch (apk copy vs locked runtime)"
            return null
        }
        loaderCache = candidate to installDir.canonicalFile.hashCode().toLong()
        candidate
    } catch (e: Exception) {
        loaderError = "check: ${e.javaClass.simpleName}: ${e.message}"
        null
    }
}

internal fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val chunk = ByteArray(65536)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            digest.update(chunk, 0, n)
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
