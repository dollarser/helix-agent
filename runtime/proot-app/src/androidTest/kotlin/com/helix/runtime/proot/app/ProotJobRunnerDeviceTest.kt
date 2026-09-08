package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.core.sha256Hex
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRefusal
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-084 device acceptance: the job engine end-to-end on a real device.
 *
 * These tests run IN the companion process (androidTest target = the app), so
 * they drive the same [ProotJobRunner] singleton the service binder exposes;
 * the cross-APK Binder path itself is covered by the main app's E2E suite.
 *
 * Every test ensures an ACTIVE runtime first (idempotent: the HXA-082 suite's
 * @After wipes the runtime dir, and test class order is not guaranteed).
 *
 * Device-verified premises this suite builds on (recorded in the completion
 * record): PRoot 5.1.107 executes on the emulator images; the Termux build
 * requires LD_LIBRARY_PATH/PROOT_LOADER/PROOT_TMP_DIR; the host setsid gives
 * the job its own process group (pgid == pid) and `kill -9 -<pid>` kills the
 * whole tree.
 */
@RunWith(AndroidJUnit4::class)
class ProotJobRunnerDeviceTest {
    private lateinit var context: Context
    private lateinit var runner: ProotJobRunner
    private val jobIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureActiveRuntime()
        runner = ProotJobRunner.get(context)
    }

    @After
    fun tearDown() {
        // Keep the journal clean (the budget test deliberately fills it).
        jobIds.forEach { id ->
            runner.query(id)?.let { record ->
                if (!record.state.isTerminal) runner.cancel(id)
            }
            val dir = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context)).jobDir(id)
            // KEEP failure.txt evidence on disk (copied to logcat below); the
            // journal entry itself is removed so the budget stays usable.
            val failure = File(dir, "failure.txt")
            if (failure.isFile) {
                System.err.println("JOB-FAILURE-EVIDENCE $id: " + failure.readText())
                failure.copyTo(File(context.cacheDir, "$id-failure.txt"), overwrite = true)
            }
            dir.deleteRecursively()
        }
        jobIds.clear()
    }

    // ------------------------------------------------------------------ helpers

    /** Single sha256 over raw bytes (the core [sha256Hex] takes a MessageDigest). */
    private fun sha256OfBytes(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun ensureActiveRuntime() {
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        RootFsInstaller.currentActive(root)?.let { return }
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
        val outcome =
            RootFsInstaller.install(
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    lock,
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                ),
            )
        assertTrue("runtime install failed: $outcome", outcome is com.helix.runtime.proot.core.InstallOutcome.Success)
    }

    private fun nextJobId(): String =
        "job_" + (0 until 12).map { "0123456789ab"[(Math.random() * 12).toInt()] }.joinToString("")

    private fun buildInputArchive(files: Map<String, String>): Pair<File, String> {
        val dir = File(context.cacheDir, "jobtest-" + nextJobId()).apply { mkdirs() }
        val entries =
            files
                .map { (path, content) ->
                    File(dir, path).apply {
                        parentFile?.mkdirs()
                        writeText(content)
                    }
                    JobManifestEntry(
                        path,
                        sha256Hex(
                            MessageDigest.getInstance("SHA-256").apply {
                                update(content.toByteArray())
                            },
                        ),
                        content.toByteArray().size.toLong(),
                    )
                }.sortedBy { it.path }
        val document = JobManifestCodec.encode(JobManifest(entries))
        val archive = File(dir, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(document)
            entries.forEach { entry -> writer.writeEntry(entry.path, File(dir, entry.path)) }
        }
        return archive to sha256OfBytes(document.toByteArray())
    }

    private fun specFor(
        jobId: String,
        executionId: String,
        command: ProotJobCommand,
        inputManifestSha256: String,
        deadlineMs: Long = 60_000L,
    ): ProotJobSpec =
        ProotJobSpec(
            executionId = executionId,
            jobId = jobId,
            command = command,
            relativeWorkingDirectory = "",
            environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to "/root"),
            deadlineMs = deadlineMs,
            maxOutputBytes = 1_048_576L,
            inputManifestSha256 = inputManifestSha256,
        )

    /** Submits (in-process) and returns the accepted record; the PFDs come from real files. */
    private fun submit(
        spec: ProotJobSpec,
        inputArchive: File,
        owner: android.os.IBinder? = null,
    ): ProotJobSubmitResult {
        val inputPfd = ParcelFileDescriptor.open(inputArchive, ParcelFileDescriptor.MODE_READ_ONLY)
        val outputFile = File(context.cacheDir, "${spec.jobId}-output.zip")
        outputFile.delete()
        val outputPfd =
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        val result =
            if (owner == null) {
                runner.submit(spec, inputPfd, outputPfd)
            } else {
                runner.submitOwned(owner, spec, inputPfd, outputPfd)
            }
        if (result is ProotJobSubmitResult.Accepted || result is ProotJobSubmitResult.Duplicate) {
            outputFile.deleteOnExit()
        }
        return result
    }

    private fun outputFileFor(jobId: String): File = File(context.cacheDir, "$jobId-output.zip")

    private fun waitForTerminal(
        jobId: String,
        timeoutMs: Long = 120_000L,
    ): ProotJobRecord {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val record = runner.query(jobId) ?: error("job disappeared: $jobId")
            if (record.state.isTerminal) return record
            Thread.sleep(200L)
        }
        error("job $jobId did not reach a terminal state within ${timeoutMs}ms")
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun aJobRunsUnderProotAndDeliversVerifiedOutput() {
        val (archive, inputSha) = buildInputArchive(mapOf("in.txt" to "IN-CONTENT"))
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-jobtest-$jobId",
                ProotJobCommand.Argv(
                    listOf(
                        "/bin/sh",
                        "-c",
                        "cat /workspace/in.txt > in.txt && echo artifact > out.txt && " +
                            "echo STDOUT_LINE && echo STDERR_LINE >&2",
                    ),
                ),
                inputSha,
            )
        val result = submit(spec, archive)
        assertTrue("expected Accepted, got $result", result is ProotJobSubmitResult.Accepted)

        val record = waitForTerminal(jobId)
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertEquals(inputSha, record.inputManifestSha256)
        assertTrue(record.stdoutBytes >= 12L)
        assertTrue(record.stderrBytes >= 12L)
        val outputManifest = record.outputManifestSha256
        assertNotNull("SUCCEEDED must carry an output manifest hash", outputManifest)

        // The client side of the proof: extract + re-verify the delivered archive.
        val extraction = ZipJobExtractor.extract(outputFileFor(jobId), File(context.cacheDir, "$jobId-extracted"))
        assertEquals(outputManifest, extraction.manifestSha256)
        val paths =
            extraction.manifest.entries
                .map { it.path }
                .toSet()
        assertTrue("out.txt missing from the output", "out.txt" in paths)
        assertTrue("stdout.txt missing from the output", "stdout.txt" in paths)
        val stdout = File(context.cacheDir, "$jobId-extracted/stdout.txt").readText()
        assertTrue(stdout.contains("STDOUT_LINE"))
        val artifact = File(context.cacheDir, "$jobId-extracted/out.txt").readText()
        assertEquals("artifact\n", artifact)
    }

    @Test
    fun aFailedInitialDeliveryKeepsTheVerifiedResultRecoverable() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val output = outputFileFor(jobId).apply { writeText("") }
        val spec =
            specFor(
                jobId,
                "exec-jobtest-$jobId",
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo RECOVER_ME")),
                inputSha,
            )
        val submitted =
            runner.submit(
                spec,
                ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY),
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY),
            )
        assertTrue(submitted is ProotJobSubmitResult.Accepted)
        val record = waitForTerminal(jobId)
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertEquals(0L, output.length())
        val recovered = File(context.cacheDir, "$jobId-recovered.zip")
        requireNotNull(runner.fetchResult(jobId)).use { result ->
            assertEquals(record, result.record)
            ParcelFileDescriptor.AutoCloseInputStream(result.descriptor).use { input ->
                recovered.outputStream().use { input.copyTo(it) }
            }
        }
        val directory = File(context.cacheDir, "$jobId-recovered")
        try {
            val extracted = ZipJobExtractor.extract(recovered, directory)
            assertEquals(record.outputManifestSha256, extracted.manifestSha256)
            assertEquals("RECOVER_ME\n", File(directory, "stdout.txt").readText())
        } finally {
            recovered.delete()
            directory.deleteRecursively()
            output.delete()
        }
    }

    @Test
    fun aJobIsStartedExactlyOnce() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        val executionId = "exec-dup-$jobId"
        jobIds += jobId
        val spec = specFor(jobId, executionId, ProotJobCommand.Argv(listOf("/bin/sh", "-c", "sleep 30")), inputSha)
        val first = submit(spec, archive)
        assertTrue(first is ProotJobSubmitResult.Accepted)
        // Same jobId: duplicate, no second start.
        val second = submit(spec, archive)
        assertTrue("expected Duplicate, got $second", second is ProotJobSubmitResult.Duplicate)
        if (second is ProotJobSubmitResult.Duplicate) {
            assertEquals(jobId, second.record.jobId)
        }
        // Same executionId under a NEW jobId: still the same job.
        val otherJobId = nextJobId()
        jobIds += otherJobId
        val third =
            submit(
                specFor(otherJobId, executionId, ProotJobCommand.Argv(listOf("/bin/sh", "-c", "true")), inputSha),
                archive,
            )
        assertTrue("expected Duplicate for the same executionId, got $third", third is ProotJobSubmitResult.Duplicate)
        if (third is ProotJobSubmitResult.Duplicate) {
            assertEquals(jobId, third.record.jobId)
        }
        // Let the first job end so cleanup is clean.
        runner.cancel(jobId)
        runner.cancel(otherJobId)
        waitForTerminal(jobId)
    }

    @Test
    fun aWrongInputManifestHashIsInputInvalid() {
        val (archive, inputSha) = buildInputArchive(mapOf("x" to "y"))
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(jobId, "exec-badin-$jobId", ProotJobCommand.Argv(listOf("/bin/sh", "-c", "true")), "f".repeat(64))
        val result = submit(spec, archive)
        assertTrue(result is ProotJobSubmitResult.Accepted)
        val record = waitForTerminal(jobId)
        assertEquals(ProotJobState.INPUT_INVALID, record.state)
        assertNull(record.exitCode)
        // The untrusted input payload is deleted, the terminal proof remains.
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue(!store.jobDir(jobId).isDirectory || !File(store.jobDir(jobId), "workspace").exists())
    }

    @Test
    fun aCancelledJobIsKilledByGroupAndTerminal() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-cancel-$jobId",
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", "sleep 60 & sleep 60 & wait")),
                inputSha,
            )
        submit(spec, archive)
        // Wait for RUNNING, then cancel.
        val start = System.currentTimeMillis()
        while (runner.query(jobId)?.state != ProotJobState.RUNNING) {
            assertTrue("did not reach RUNNING", System.currentTimeMillis() - start < 30_000L)
            Thread.sleep(100L)
        }
        runner.cancel(jobId)
        val record = waitForTerminal(jobId, 30_000L)
        assertEquals(ProotJobState.CANCELLED, record.state)
    }

    @Test
    fun cancellingAQueuedJobPreventsItsCommandFromStarting() {
        val (archive, hash) = buildInputArchive(emptyMap())
        val first = nextJobId()
        val queued = nextJobId()
        jobIds += first
        jobIds += queued
        val sleeping = specFor(first, "exec-$first", ProotJobCommand.Argv(listOf("/bin/sleep", "2")), hash)
        assertTrue(submit(sleeping, archive) is ProotJobSubmitResult.Accepted)
        val command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo SHOULD_NOT_RUN > forbidden.txt"))
        assertTrue(submit(specFor(queued, "exec-$queued", command, hash), archive) is ProotJobSubmitResult.Accepted)
        assertEquals(ProotJobState.PENDING, runner.query(queued)?.state)
        runner.cancel(queued)
        waitForTerminal(first, 10000)
        assertEquals(ProotJobState.CANCELLED, waitForTerminal(queued, 10000).state)
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue(!File(store.jobDir(queued), "workspace/forbidden.txt").exists())
    }

    @Test
    fun queuedOwnerDeathCancelsWithoutAcceptingADuplicateOwner() {
        val (archive, hash) = buildInputArchive(emptyMap())
        val first = nextJobId()
        val queued = nextJobId()
        jobIds += listOf(first, queued)
        val sleeping = specFor(first, "exec-$first", ProotJobCommand.Argv(listOf("/bin/sleep", "2")), hash)
        assertTrue(submit(sleeping, archive) is ProotJobSubmitResult.Accepted)
        val owner = ControllableOwnerFixture()
        val replacement = ControllableOwnerFixture()
        val command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo SHOULD_NOT_RUN > forbidden.txt"))
        val spec = specFor(queued, "exec-$queued", command, hash)
        assertTrue(submit(spec, archive, owner.binder) is ProotJobSubmitResult.Accepted)
        assertTrue(submit(spec, archive, replacement.binder) is ProotJobSubmitResult.Duplicate)
        assertEquals(1, owner.links.get())
        assertEquals(0, replacement.links.get())
        replacement.die()
        assertEquals(ProotJobState.PENDING, runner.query(queued)?.state)
        owner.die()
        assertEquals(1, owner.unlinks.get())
        waitForTerminal(first, 10000)
        assertEquals(ProotJobState.CANCELLED, waitForTerminal(queued, 10000).state)
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue(!File(store.jobDir(queued), "workspace/forbidden.txt").exists())
        assertEquals(1, owner.unlinks.get())
    }

    @Test
    fun normalCompletionReleasesTheOwnerLink() {
        val (archive, hash) = buildInputArchive(emptyMap())
        val job = nextJobId()
        jobIds += job
        val owner = ControllableOwnerFixture()
        val spec = specFor(job, "exec-$job", ProotJobCommand.Argv(listOf("/bin/true")), hash)
        assertTrue(submit(spec, archive, owner.binder) is ProotJobSubmitResult.Accepted)
        val completed = waitForTerminal(job, 10000)
        assertEquals(ProotJobState.SUCCEEDED, completed.state)
        val deadline = System.currentTimeMillis() + 5000
        while (owner.unlinks.get() == 0) {
            assertTrue(System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        assertEquals(1, owner.links.get())
        assertEquals(1, owner.unlinks.get())
        owner.die()
        assertEquals(completed, runner.query(job))
    }

    @Test
    fun anAlreadyDeadOwnerNeverStartsItsCommand() {
        val (archive, hash) = buildInputArchive(emptyMap())
        val job = nextJobId()
        jobIds += job
        val command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo SHOULD_NOT_RUN > forbidden.txt"))
        val spec = specFor(job, "exec-$job", command, hash)
        assertTrue(submit(spec, archive, delayedDeadOwner()) is ProotJobSubmitResult.Accepted)
        assertEquals(ProotJobState.CANCELLED, waitForTerminal(job, 10000).state)
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue(!File(store.jobDir(job), "workspace/forbidden.txt").exists())
    }

    @Test
    fun aPastDeadlineJobIsTimedOut() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-timeout-$jobId",
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", "sleep 60")),
                inputSha,
                deadlineMs = 5_000L,
            )
        submit(spec, archive)
        val record = waitForTerminal(jobId, 60_000L)
        assertEquals(ProotJobState.TIMED_OUT, record.state)
    }

    @Test
    fun aReconciledJobLosesItsPayloadButKeepsItsProof() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-reconcile-$jobId",
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo done > out.txt")),
                inputSha,
            )
        submit(spec, archive)
        val terminal = waitForTerminal(jobId)
        assertEquals(ProotJobState.SUCCEEDED, terminal.state)
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue(
            "payload should exist before reconcile",
            store
                .jobDir(jobId)
                .listFiles()
                .orEmpty()
                .isNotEmpty(),
        )
        val reconciled = runner.reconcile(jobId, System.currentTimeMillis())
        assertNotNull(reconciled)
        assertEquals(ProotJobState.SUCCEEDED, reconciled?.state)
        assertNotNull("reconciled record must carry the stamp", reconciled?.reconciledAtEpochMs)
        // The payload is gone; only the terminal proof (record.json) survives.
        val remaining =
            store
                .jobDir(jobId)
                .listFiles()
                .orEmpty()
                .map { it.name }
        assertEquals(listOf("record.json"), remaining)
        // The commit is stable across reconciliation.
        assertEquals(terminal.terminalCommit, reconciled?.terminalCommit)
    }

    @Test
    fun theJournalBudgetRejectsWhenFull() {
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        // Fill the journal to its 128-entry cap with PENDING records (non-evictable).
        val fillerIds = mutableListOf<String>()
        val existing = store.entries().size
        val toFill = ProotJobStore.MAX_ENTRIES - existing
        repeat(toFill) { i ->
            val id = "job_" + ("f0" + i.toString(16).padStart(10, '0')).take(12)
            if (store.load(id) == null) {
                store.put(
                    ProotJobRecord(
                        jobId = id,
                        executionId = "exec-fill-$i",
                        inputManifestSha256 = "0".repeat(64),
                        state = ProotJobState.PENDING,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                fillerIds += id
            }
        }
        assertEquals(ProotJobStore.MAX_ENTRIES, store.entries().size)
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(jobId, "exec-budget-$jobId", ProotJobCommand.Argv(listOf("/bin/sh", "-c", "true")), inputSha)
        val result = submit(spec, archive)
        assertTrue("expected JOURNAL_FULL, got $result", result is ProotJobSubmitResult.Rejected)
        if (result is ProotJobSubmitResult.Rejected) {
            assertEquals(ProotJobRefusal.JOURNAL_FULL, result.refusal)
        }
        // Clean up the fillers so other suites keep a usable journal.
        fillerIds.forEach { store.jobDir(it).deleteRecursively() }
    }

    @Test
    fun anOrphanedRecordFromADeadIncarnationIsSweptWithoutKillingInnocents() {
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        val jobId = "job_" + "ab".repeat(6)
        jobIds += jobId
        // A PENDING record claiming a dead pid (2^20: far beyond any live pid).
        store.put(
            ProotJobRecord(
                jobId = jobId,
                executionId = "exec-orphan-test",
                inputManifestSha256 = "0".repeat(64),
                state = ProotJobState.PENDING,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        File(store.jobDir(jobId), "proc.txt").writeText("pid=1048576\nstartTicks=12345\n")
        runner.sweepOrphans()
        val record = runner.query(jobId)
        assertEquals(ProotJobState.ORPHANED, record?.state)
        assertNotNull(record?.terminalAtEpochMs)
        assertNull("ORPHANED carries no exit code", record?.exitCode)
    }

    @Test
    fun aScriptModeJobRunsTheExplicitShellScript() {
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-script-$jobId",
                ProotJobCommand.Script("echo scripted > out.txt; echo SCRIPT_STDOUT"),
                inputSha,
            )
        submit(spec, archive)
        val record = waitForTerminal(jobId)
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        val extraction = ZipJobExtractor.extract(outputFileFor(jobId), File(context.cacheDir, "$jobId-extracted"))
        assertEquals(record.outputManifestSha256, extraction.manifestSha256)
        assertEquals("scripted\n", File(context.cacheDir, "$jobId-extracted/out.txt").readText())
    }

    @Test
    fun theWorkspaceIsolatedFromTheHostAndTheInstall() {
        // The job's /tmp is the job's own dir, never the install's shared tmp.
        val (archive, inputSha) = buildInputArchive(emptyMap())
        val jobId = nextJobId()
        jobIds += jobId
        val spec =
            specFor(
                jobId,
                "exec-iso-$jobId",
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", "echo marker > /tmp/tmptest; ls / | tr '\\n' ' '")),
                inputSha,
            )
        submit(spec, archive)
        val record = waitForTerminal(jobId)
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        val store = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context))
        assertTrue("the job's /tmp must land in the job dir", File(store.jobDir(jobId), "tmp/tmptest").isFile)
        // The install's shared tmp must not have picked it up.
        val installId = RootFsInstaller.currentActive(ProotRuntimeInstaller.runtimeRoot(context))!!.installId
        assertTrue(!File(ProotRuntimeInstaller.runtimeRoot(context), "$installId/tmp/tmptest").exists())
    }
}
