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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-086 isolation acceptance (roadmap §12: "Runtime 不能读主 App dataDir、
 * 共享存储或联网"): the guest's visible universe is the chroot tree + the
 * binds the runner sets up (`/dev`, `/proc`, the job's `/tmp`, the job's
 * `/workspace`). Nothing else — specifically:
 *
 * - the HOST app-data paths (the companion's own filesDir, and by the same
 *   construction the main app's filesDir — covered from the main-app side by
 *   `ProotRuntimeIsolationE2eDeviceTest`) are invisible: the guest sees
 *   ENOENT, and a read attempt cannot even fail open;
 * - shared storage (`/sdcard`, `/storage/emulated/0`) is not bound and the
 *   companion declares no storage permission;
 * - the network: the companion declares NO INTERNET permission, and a
 *   guest-side connect/DNS attempt fails (the emulator itself has network —
 *   a success here would be a real isolation breach).
 */
@RunWith(AndroidJUnit4::class)
class ProotIsolationDeviceTest {
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
            File(context.filesDir, "iso-ran-" + id).delete()
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

    /** Runs one argv job (empty input snapshot); returns (record, exitCode, stdout, stderr). */
    @Suppress("LongMethod")
    private fun runToTerminal(
        command: List<String>,
        deadlineMs: Long = 60_000L,
    ): Triple<ProotJobRecord, Int, String> {
        val jobId = nextJobId()
        jobIds += jobId
        File(context.filesDir, "iso-ran-" + jobId).writeText("ran")
        val cacheDir = File(context.cacheDir, "iso-$jobId").apply { mkdirs() }
        val emptyManifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val archive = File(cacheDir, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer -> writer.writeManifest(emptyManifest) }
        val inputSha =
            sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(emptyManifest.encodeToByteArray()) })
        val spec =
            ProotJobSpec(
                executionId = "exec-iso-" + System.nanoTime().toUInt().toString(16),
                jobId = jobId,
                command = ProotJobCommand.Argv(command),
                relativeWorkingDirectory = "",
                environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to "/root"),
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
        val extracted = File(cacheDir, "extracted")
        ZipJobExtractor.extract(outputFile, extracted)
        val stdout = File(extracted, "stdout.txt").takeIf { it.isFile }?.readText() ?: ""
        return Triple(record, record.exitCode ?: -1, stdout)
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
    fun theGuestCannotReadTheCompanionAppData() {
        // A marker the guest could read ONLY if host app-data were visible.
        val marker = File(context.filesDir, "iso-086-marker.txt").apply { writeText("MUST-NOT-BE-READ") }
        try {
            val (record, exit, stdout) =
                runToTerminal(
                    listOf("/bin/sh", "-c", "cat " + marker.absolutePath + " || echo READ-FAILED"),
                )
            // The sh command keeps the exit 0 either way: the assertion is on
            // WHAT the guest saw — ENOENT (READ-FAILED), never the contents.
            assertEquals(ProotJobState.SUCCEEDED, record.state)
            assertEquals(0, exit)
            assertTrue("the guest must NOT see the host app-data file: $stdout", "READ-FAILED" in stdout)
            assertFalse("the marker content must never reach the guest: $stdout", "MUST-NOT-BE-READ" in stdout)
        } finally {
            marker.delete()
        }
    }

    @Test
    fun theGuestCannotReachSharedStorage() {
        val (record, exit, stdout) =
            runToTerminal(
                listOf(
                    "/bin/sh",
                    "-c",
                    "ls /storage/emulated/0 2>/dev/null || echo NO-SHARED-1; " +
                        "ls /sdcard 2>/dev/null || echo NO-SHARED-2",
                ),
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, exit)
        assertTrue("shared storage must be unreachable: $stdout", "NO-SHARED-1" in stdout)
        assertTrue("sdcard must be unreachable: $stdout", "NO-SHARED-2" in stdout)
    }

    @Test
    fun theGuestCannotReachTheNetwork() {
        // The emulator ITSELF has network: a success here is a real isolation
        // breach. A TCP connect to a public address and a DNS lookup must BOTH
        // fail in the guest; the job succeeds only in the no-network case.
        val (record, exit, stdout) =
            runToTerminal(
                listOf(
                    "/usr/bin/python3",
                    "-c",
                    "import socket, sys\n" +
                        "try:\n" +
                        "    socket.create_connection(('93.184.216.34', 443), 3)\n" +
                        "    sys.exit(99)\n" +
                        "except OSError:\n" +
                        "    pass\n" +
                        "try:\n" +
                        "    socket.gethostbyname('example.com')\n" +
                        "    sys.exit(99)\n" +
                        "except OSError:\n" +
                        "    print('NO-NETWORK-OK')\n",
                ),
            )
        assertEquals("the guest must have NO network (connect + DNS both fail)", ProotJobState.SUCCEEDED, record.state)
        assertEquals(0, exit)
        assertTrue("the no-network marker must be printed: $stdout", "NO-NETWORK-OK" in stdout)
    }

    @Test
    fun theCompanionPackageDeclaresNoInternetOrStoragePermissions() {
        val info =
            context.packageManager.getPackageInfo(context.packageName, 0)
        val declared = info.requestedPermissions ?: emptyArray()
        assertFalse("INTERNET must not be declared: $declared", "android.permission.INTERNET" in declared)
        val storagePermissions =
            declared.filter {
                it.startsWith("android.permission.READ_EXTERNAL_STORAGE") ||
                    it.startsWith("android.permission.WRITE_EXTERNAL_STORAGE") ||
                    it == "android.permission.MANAGE_EXTERNAL_STORAGE"
            }
        assertTrue("no storage permission may be declared: $storagePermissions", storagePermissions.isEmpty())
    }
}
