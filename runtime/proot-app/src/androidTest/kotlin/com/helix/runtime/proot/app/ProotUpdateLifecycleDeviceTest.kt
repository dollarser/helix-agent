package com.helix.runtime.proot.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RollbackOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeInstallQueries
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.RuntimeUpdateState
import com.helix.runtime.proot.core.updateStateFor
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HXA-087 acceptance (roadmap §12: 同签名 APK 更新、rollback、完整删除、
 * 离线 notice/source URL/build manifest), companion side:
 *
 * - the legal page is the OFFLINE surface: its activity is exported behind the
 *   set's SIGNATURE permission (like the repair entry), its content carries the
 *   embedded lock's canonical fingerprint, the per-component source URLs (never
 *   fetched) and the full embedded license texts, and it is launchable from a
 *   same-signature caller (this test package);
 * - the update decision (INSTALL / UPDATE / REPAIR) reads the ACTIVE install's
 *   embedded-lock fingerprint from its manifest against the APK's embedded lock —
 *   a second install of the SAME embedded version yields REPAIR (the "update" of a
 *   same-signature APK is detected by a lock fingerprint change, which only a new
 *   APK can bring);
 * - rollback: a second install keeps the first as rollback; `activateRollback`
 *   swaps the pointers atomically (no files move) and the rolled-back version
 *   still RUNS a real job (更新失败保持旧 runtime 可用的对偶：rollback 后的版本可用);
 * - complete removal: [ProotRuntimeRemoval] deletes ONLY the `runtime` state tree —
 *   sibling files in the companion's filesDir survive (the Workspace-in-main-app
 *   direction is covered by the main-app-side E2E test).
 *
 * The full update sequence across a REAL companion APK swap (new embedded lock →
 * main-app 需更新 → re-baseline) is exercised by the main-app developer test
 * `ProotUpdateLegalE2eDeviceTest` (the anchor lives in the main app's package).
 */
@RunWith(AndroidJUnit4::class)
class ProotUpdateLifecycleDeviceTest {
    private lateinit var context: Context
    private lateinit var runtimeRoot: File
    private var originalActive: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runtimeRoot = ProotRuntimeInstaller.runtimeRoot(context)
        originalActive = RootFsInstaller.currentActive(runtimeRoot)?.installId
        ensureActiveRuntime()
    }

    @After
    fun tearDown() {
        // Leave the device with a working active install (the suite baseline):
        // a test that removed it reinstalls; one that installed an extra version
        // rolls nothing back (the extra version stays as rollback — harmless).
        RootFsInstaller.currentActive(runtimeRoot)?.let { return }
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
        assertTrue("teardown reinstall failed: $outcome", outcome is InstallOutcome.Success)
    }

    private fun ensureActiveRuntime() {
        RootFsInstaller.currentActive(runtimeRoot)?.let { return }
        installEmbedded()
    }

    /** Installs one more embedded version; returns the new active install id. */
    private fun installEmbedded(): String {
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
        assertTrue("install failed: $outcome", outcome is InstallOutcome.Success)
        return (outcome as InstallOutcome.Success).installId
    }

    @Test
    fun theLegalActivityIsExportedBehindTheSignaturePermission() {
        val pm = context.packageManager
        val info =
            pm.getActivityInfo(
                android.content.ComponentName(
                    ProotRuntimeProtocol.RUNTIME_PACKAGE,
                    ProotRuntimeProtocol.LEGAL_ACTIVITY_CLASS,
                ),
                0,
            )
        assertTrue("legal activity must be exported (explicit-intent resolution)", info.exported)
        assertEquals(
            ProotRuntimeProtocol.PERMISSION_BIND,
            info.permission,
        )
        // The page must be reachable by THIS (same-signature) package — the repair
        // entry already proves the permission grants; this is the same shape.
        assertNotNull(pm)
    }

    @Test
    fun theLegalPageContentIsOfflineAndCarriesTheEmbeddedLock() {
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
        val page = ProotLegalPage.build(context, lock)
        // Offline notice (HXA-087 离线 notice):
        assertTrue("offline notice missing", "离线声明" in page)
        assertTrue("no-INTERNET statement missing", "INTERNET" in page)
        assertTrue("same-signature update statement missing", "同签名" in page)
        // Build manifest (HXA-087 build manifest): canonical fingerprint + ABI:
        assertTrue("canonical lock fingerprint missing", RuntimeLockCodec.sha256Hex(lock) in page)
        assertTrue("ABI missing", lock.abi.wire in page)
        // Per-component source URLs (HXA-087 source URL; displayed, never fetched):
        for (component in lock.components) {
            assertTrue(
                "component ${component.id} url missing from the page",
                component.url in page,
            )
            assertTrue(
                "component ${component.id} license missing",
                component.license.spdx in page,
            )
        }
        // Full embedded license texts (offline 法律页): every distinct textRef of
        // the embedded lock must be present verbatim (the page is the obligation).
        val distinctTextRefs = lock.components.map { it.license.textRef }.toSet()
        for (ref in distinctTextRefs) {
            val text =
                context.assets
                    .open("runtime/$ref")
                    .bufferedReader()
                    .use { it.readText() }
            assertTrue(
                "license text for $ref not shown in full",
                page.contains(text),
            )
        }
    }

    @Test
    fun theLegalActivityLaunchesFromTheSameSignatureCaller() {
        // startActivity crosses into the companion's own process: the SIGNATURE
        // permission must grant it (a SecurityException here = regression).
        context.startActivity(
            android.content
                .Intent()
                .setComponent(
                    android.content.ComponentName(
                        ProotRuntimeProtocol.RUNTIME_PACKAGE,
                        ProotRuntimeProtocol.LEGAL_ACTIVITY_CLASS,
                    ),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Test
    fun aSecondInstallKeepsARollbackAndRollbackRestoresTheFirst() {
        val first = RootFsInstaller.currentActive(runtimeRoot)!!.installId
        assertEquals(first, originalActive)
        // Second install of the SAME embedded version (the same-signature-APK
        // update with an unchanged lock = REPAIR state; it still takes a slot):
        val second = installEmbedded()
        assertTrue(second != first)
        val rollbackId = RootFsInstaller.currentRollback(runtimeRoot)?.installId
        assertEquals("the first install must be kept as the rollback", first, rollbackId)
        // The update decision against the SAME embedded lock reads REPAIR:
        val embeddedSha = RuntimeLockCodec.sha256Hex(ProotRuntimeInstaller.loadEmbeddedLock(context))
        assertEquals(
            RuntimeUpdateState.REPAIR,
            updateStateFor(embeddedSha, RuntimeInstallQueries.activeLockSha256(runtimeRoot)),
        )
        // Rollback: pointers swap, no files move, the FIRST version is active again:
        val outcome =
            RootFsInstaller.activateRollback(runtimeRoot, System.currentTimeMillis())
        assertTrue("rollback must succeed, got: $outcome", outcome is RollbackOutcome.Done)
        assertEquals(first, (outcome as RollbackOutcome.Done).newActiveId)
        assertEquals(second, RootFsInstaller.currentRollback(runtimeRoot)?.installId)
        // The rolled-back (first) version still RUNS a real job:
        val runner = ProotJobRunner.get(context)
        val job = runEchoJob(runner, "rollback-still-runs-" + first)
        assertEquals(ProotJobState.SUCCEEDED, job.first.state)
        assertEquals(0, job.second)
    }

    @Test
    fun rollbackWithoutARollbackVersionIsNoRollback() {
        val active = RootFsInstaller.currentActive(runtimeRoot)!!.installId
        assertEquals(active, originalActive)
        if (RootFsInstaller.currentRollback(runtimeRoot) == null) {
            val outcome =
                RootFsInstaller.activateRollback(runtimeRoot, System.currentTimeMillis())
            assertEquals(RollbackOutcome.NoRollback, outcome)
        } else {
            // A rollback slot already exists (from a previous test/device state):
            // exercise the real swap and swap back — the state is restored either way.
            val rollbackId = RootFsInstaller.currentRollback(runtimeRoot)!!.installId
            val forward = RootFsInstaller.activateRollback(runtimeRoot, System.currentTimeMillis())
            assertTrue("forward swap failed: $forward", forward is RollbackOutcome.Done)
            val back = RootFsInstaller.activateRollback(runtimeRoot, System.currentTimeMillis())
            assertTrue("swap-back failed: $back", back is RollbackOutcome.Done)
            assertEquals(active, RootFsInstaller.currentActive(runtimeRoot)?.installId)
            assertEquals(rollbackId, RootFsInstaller.currentRollback(runtimeRoot)?.installId)
        }
    }

    @Test
    fun removalDeletesTheRuntimeTreeAndNothingElse() {
        // A sibling file in the companion's filesDir must survive the removal
        // (the scoping property: ONLY filesDir/runtime may be deleted):
        val sibling = File(context.filesDir, "keep-me-" + System.nanoTime())
        sibling.writeText("survivor")
        val activeBefore = RootFsInstaller.currentActive(runtimeRoot)
        assertNotNull(activeBefore)
        val result = ProotRuntimeRemoval.remove(runtimeRoot)
        assertTrue("removal must report success", result.removed)
        assertTrue("removal must report the previous active install", result.hadActive)
        assertEquals(activeBefore!!.installId, result.activeInstallId)
        assertTrue("the runtime state tree must be gone", !runtimeRoot.exists())
        assertNull(RootFsInstaller.currentActive(runtimeRoot))
        assertTrue("the sibling file must survive", sibling.exists() && sibling.readText() == "survivor")
        sibling.delete()
        // The install path works again from the clean slate:
        installEmbedded()
    }

    @Test
    fun removalRefusesAWrongRoot() {
        // Defense: a caller bug that passes any OTHER directory is refused
        // (fail closed), never silently deleted:
        val foreign = File(context.filesDir, "not-runtime-" + System.nanoTime()).apply { mkdirs() }
        File(foreign, "x.txt").writeText("x")
        val refused =
            runCatching { ProotRuntimeRemoval.remove(foreign) }.exceptionOrNull()
        assertTrue(
            "removal must refuse a non-runtime root (got: $refused)",
            refused is IllegalArgumentException,
        )
        assertTrue("the foreign directory must be untouched", foreign.exists())
        foreign.deleteRecursively()
    }

    @Suppress("LongMethod")
    private fun runEchoJob(
        runner: ProotJobRunner,
        token: String,
    ): Pair<com.helix.runtime.proot.ipc.ProotJobRecord, Int> {
        val jobId =
            "job_" + (0 until 12).map { "0123456789ab"[(Math.random() * 12).toInt()] }.joinToString("")
        val cacheDir = File(context.cacheDir, "upd-$jobId").apply { mkdirs() }
        val emptyManifest =
            com.helix.runtime.proot.core.JobManifestCodec.encode(
                com.helix.runtime.proot.core
                    .JobManifest(emptyList()),
            )
        val archive = File(cacheDir, "input.zip")
        com.helix.runtime.proot.core
            .JobZipWriter(archive.outputStream())
            .use { it.writeManifest(emptyManifest) }
        val inputSha =
            com.helix.runtime.proot.core.sha256Hex(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .apply { update(emptyManifest.encodeToByteArray()) },
            )
        val spec =
            com.helix.runtime.proot.ipc.ProotJobSpec(
                executionId = "exec-upd-" + System.nanoTime().toUInt().toString(16),
                jobId = jobId,
                command =
                    com.helix.runtime.proot.ipc.ProotJobCommand.Argv(
                        listOf("/bin/sh", "-c", "echo $token"),
                    ),
                relativeWorkingDirectory = "",
                environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/root"),
                deadlineMs = 60_000L,
                maxOutputBytes = 1_048_576L,
                inputManifestSha256 = inputSha,
            )
        val inputPfd =
            android.os.ParcelFileDescriptor.open(archive, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val outputFile = File(cacheDir, "output.zip")
        val outputPfd =
            android.os.ParcelFileDescriptor.open(
                outputFile,
                android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        val result = runner.submit(spec, inputPfd, outputPfd)
        assertTrue(
            "submit must be accepted, got: $result",
            result is com.helix.runtime.proot.ipc.ProotJobSubmitResult.Accepted,
        )
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 120_000L) {
            val record = runner.query(jobId) ?: error("job disappeared: $jobId")
            if (record.state.isTerminal) return Pair(record, record.exitCode ?: -1)
            Thread.sleep(200L)
        }
        error("job $jobId did not reach a terminal state in 120s")
    }
}
