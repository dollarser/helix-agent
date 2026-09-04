package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.core.sha256Hex
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-086 smoke: the baseline tools of the locked runtime (HXA-081:
 * python3 / nodejs / git / ripgrep) actually EXECUTE in the guest.
 *
 * FRESHNESS CONTRACT (anti-stale-green): the python smoke echoes a per-run
 * random token passed through the guest environment — a submission that were
 * ever short-circuited as a duplicate (stale journal, cached anything) could
 * only return the PREVIOUS job's output and could not echo the new token.
 *
 * This is the 4 KiB-emulator smoke baseline. The task's REAL-device
 * requirement (arm64 4 KiB AND 16 KiB page devices) is a separate acceptance
 * gate (no real devices in this environment — recorded as the HXA-086
 * real-device gap); the 16 KiB ELF compatibility is covered structurally by
 * the HXA-081 alignment gate (360/360 LOAD segments >= 16384) + the
 * installer's per-page-size pre-check that blocks install (i.e. distribution)
 * on an incompatible device.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions")
class ProotSmokeDeviceTest {
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
        jobIds.forEach { id ->
            runner.query(id)?.let { record ->
                if (!record.state.isTerminal) runner.cancel(id)
            }
            ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context)).jobDir(id).deleteRecursively()
            File(context.filesDir, "smoke-ran-" + id).delete()
        }
        jobIds.clear()
    }

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

    /**
     * Runs one argv job with an empty input snapshot; returns (record,
     * extracted stdout, the submitted jobId).
     */
    @Suppress("LongMethod")
    private fun runToTerminal(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        deadlineMs: Long = 90_000L,
    ): Triple<ProotJobRecord, String, String> {
        val jobId = nextJobId()
        jobIds += jobId
        // Host-side freshness marker: this submission happened (verifiable after the run).
        File(context.filesDir, "smoke-ran-" + jobId).writeText("ran")
        val cacheDir = File(context.cacheDir, "smoke-$jobId").apply { mkdirs() }
        val emptyManifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val archive = File(cacheDir, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer -> writer.writeManifest(emptyManifest) }
        val inputSha =
            sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(emptyManifest.encodeToByteArray()) })
        val spec =
            ProotJobSpec(
                executionId = "exec-smoke-" + System.nanoTime().toUInt().toString(16),
                jobId = jobId,
                command = ProotJobCommand.Argv(command),
                relativeWorkingDirectory = "",
                environment = environment,
                deadlineMs = deadlineMs,
                maxOutputBytes = 1_048_576L,
                inputManifestSha256 = inputSha,
            )
        val inputPfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val outputFile = File(cacheDir, "output.zip")
        val outputPfd =
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        val result = runner.submit(spec, inputPfd, outputPfd)
        assertTrue("submit must be accepted, got: $result", result is ProotJobSubmitResult.Accepted)
        val record = waitForTerminal(jobId, 120_000L)
        var stdout = ""
        if (record.outputManifestSha256 != null) {
            ZipJobExtractor.extract(outputFile, File(cacheDir, "extracted"))
            stdout = File(File(cacheDir, "extracted"), "stdout.txt").takeIf { it.isFile }?.readText() ?: ""
        }
        return Triple(record, stdout, jobId)
    }

    private fun waitForTerminal(
        jobId: String,
        timeoutMs: Long,
    ): ProotJobRecord {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val record = runner.query(jobId) ?: error("job disappeared: $jobId")
            if (record.state.isTerminal) return record
            Thread.sleep(200L)
        }
        error("job $jobId did not reach a terminal state within ${timeoutMs}ms")
    }

    @Test
    fun python3RunsAndComputes() {
        // Self-identifying: the guest must echo THIS submission's random token
        // (passed via the guest environment) — a stale/duplicate short-circuit
        // could only return the previous job's output, never the new token.
        val token = "smoke-" + System.nanoTime().toUInt().toString(16)
        val (record, stdout, jobId) =
            runToTerminal(
                listOf(
                    "/usr/bin/python3",
                    "-c",
                    "import os; print(6 * 7); print('PY-SMOKE-OK ' + os.environ['SMOKE_TOKEN'])",
                ),
                environment = mapOf("SMOKE_TOKEN" to token),
            )
        assertEquals("python3 must succeed: ${record.state}", ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertEquals("the record must be this submission's job", jobId, record.jobId)
        assertTrue("stdout must carry the computation: $stdout", "42" in stdout)
        assertTrue("the guest must echo THIS submission's token (fresh execution): $stdout", token in stdout)
    }

    @Test
    fun nodeRunsAndPrints() {
        val token = "node-" + System.nanoTime().toUInt().toString(16)
        val (record, stdout, _) =
            runToTerminal(
                listOf(
                    "/bin/sh",
                    "-c",
                    "echo NODE-SMOKE-TOKEN=" + token + "; /usr/bin/node -e \"console.log(10 + 20)\"",
                ),
            )
        assertEquals("node must succeed: ${record.state}", ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertTrue("node must print: $stdout", "30" in stdout)
        assertTrue("freshness token must be echoed: $stdout", token in stdout)
    }

    @Test
    fun gitInitAddCommitAndLogInTheWorkspace() {
        val token = "git-" + System.nanoTime().toUInt().toString(16)
        val (record, stdout, _) =
            runToTerminal(
                listOf(
                    "/bin/sh",
                    "-c",
                    "git init -q repo && cd repo && echo " + token + " > a.txt && " +
                        "git add a.txt && git commit -q -m init && git log --oneline | head -1 && " +
                        "echo GIT-SMOKE-TOKEN=" + token,
                ),
                environment =
                    mapOf(
                        "GIT_AUTHOR_NAME" to "smoke",
                        "GIT_AUTHOR_EMAIL" to "smoke@example.invalid",
                        "GIT_COMMITTER_NAME" to "smoke",
                        "GIT_COMMITTER_EMAIL" to "smoke@example.invalid",
                    ),
            )
        assertEquals("git init/add/commit must succeed: ${record.state}", ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertTrue("git log must show the commit: $stdout", "init" in stdout)
        assertTrue("freshness token must be echoed: $stdout", token in stdout)
    }

    @Test
    fun ripgrepSearchesTheWorkspace() {
        val token = "rg-" + System.nanoTime().toUInt().toString(16)
        val (record, stdout, _) =
            runToTerminal(
                listOf(
                    "/bin/sh",
                    "-c",
                    "printf 'needle one\\nother\\nneedle two\\n' > scan.txt && " +
                        "rg -c needle scan.txt && echo RG-SMOKE-TOKEN=" + token,
                ),
            )
        assertEquals("ripgrep must succeed: ${record.state}", ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, record.exitCode)
        assertTrue("rg -c must count 2: $stdout", "2" in stdout)
        assertTrue("freshness token must be echoed: $stdout", token in stdout)
    }
}
