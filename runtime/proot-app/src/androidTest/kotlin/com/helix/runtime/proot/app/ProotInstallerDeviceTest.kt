package com.helix.runtime.proot.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.core.InstallFacts
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.InstallRequest
import com.helix.runtime.proot.core.ProotAssetSource
import com.helix.runtime.proot.core.RollbackOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeLock
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.RuntimeManifestCodec
import com.helix.runtime.proot.core.SmokeStatus
import com.helix.runtime.proot.core.TarStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-082 device acceptance: the real embedded assets (lock + raw tar + PRoot
 * binaries) drive the real installer on-device.
 *
 * The APP's assets are used (the main APK carries `runtime/`); the page size is
 * measured from the device so the ELF `PT_LOAD` pre-activation check exercises
 * the real page-size dimension (4 KiB emulators here; 16 KiB is HXA-086).
 */
@RunWith(AndroidJUnit4::class)
class ProotInstallerDeviceTest {
    @Rule
    @JvmField
    var tmp = TemporaryFolder()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The device's page size via the HXA-084 native seam (no more 4096 fallback). */
    private fun pageSizeBytes(): Long = ProotNative.pageSizeBytes()

    private fun installedLock(): RuntimeLock {
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
        return lock
    }

    private fun defaultRequest(
        lock: RuntimeLock,
        pageSize: Long,
        installId: String,
    ): InstallRequest =
        ProotRuntimeInstaller
            .buildInstallRequest(
                context,
                lock,
                pageSizeBytes = pageSize,
                nowEpochMs = System.currentTimeMillis(),
            ).let { req ->
                InstallRequest(
                    runtimeRoot = req.runtimeRoot,
                    lock = req.lock,
                    rootfsArchiveStream = req.rootfsArchiveStream,
                    prootAssets = req.prootAssets,
                    facts =
                        InstallFacts(
                            pageSizeBytes = pageSize,
                            nowEpochMs = System.currentTimeMillis(),
                            installIdGenerator = { installId },
                        ),
                )
            }

    /**
     * A streaming filter that flips a single byte at [atOffset] (1-based). The
     * archive must never be buffered: the test process heap is ~200 MB while the
     * archive is 137 MB, so the corruption is applied byte-by-byte in flight.
     */
    private class CorruptingInputStream(
        private val upstream: java.io.InputStream,
        private val atOffset: Long,
    ) : java.io.FilterInputStream(upstream) {
        private var read = 0L

        override fun read(): Int {
            val b = upstream.read()
            if (b < 0) return -1
            read++
            return if (read == atOffset) (b xor 0xFF) else b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = upstream.read(b, off, len)
            if (n < 0) return -1
            for (i in 0 until n) {
                if (read + i + 1 == atOffset) b[off + i] = (b[off + i].toInt() xor 0xFF).toByte()
            }
            read += n
            return n
        }
    }

    /** Same as [defaultRequest] but exactly one archive byte at [atOffset] is flipped in flight. */
    private fun corruptedRequest(
        lock: RuntimeLock,
        pageSize: Long,
        installId: String,
        atOffset: Long,
    ): InstallRequest {
        val base =
            ProotRuntimeInstaller.buildInstallRequest(context, lock, pageSize, System.currentTimeMillis())
        val name = ProotRuntimeInstaller.embeddedArchiveName(lock)
        return InstallRequest(
            runtimeRoot = base.runtimeRoot,
            lock = base.lock,
            rootfsArchiveStream = {
                CorruptingInputStream(context.assets.open("runtime/rootfs/$name"), atOffset)
            },
            prootAssets = base.prootAssets,
            facts =
                InstallFacts(
                    pageSizeBytes = pageSize,
                    nowEpochMs = System.currentTimeMillis(),
                    installIdGenerator = { installId },
                ),
        )
    }

    private fun runtimeDir(): File = ProotRuntimeInstaller.runtimeRoot(context)

    @After
    fun cleanUp() {
        runtimeDir().deleteRecursively()
    }

    @Test
    fun theEmbeddedArchiveMatchesTheLock() {
        // Streamed: the archive (137 MB) must never be buffered whole (test
        // process heap is ~200 MB).
        val lock = installedLock()
        val component = lock.component("alpine-rootfs")!!
        val name = ProotRuntimeInstaller.embeddedArchiveName(lock)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(65536)
        context.assets.open("runtime/rootfs/$name").use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                digest.update(buffer, 0, n)
            }
        }
        assertEquals(component.size, total)
        val sha = digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(component.sha256, sha)
    }

    @Test
    fun aFreshInstallLaysOutTheVersionAndActivatesIt() {
        val lock = installedLock()
        val pageSize = pageSizeBytes()
        val id = "inst_" + "ab".repeat(6)
        val outcome = RootFsInstaller.install(defaultRequest(lock, pageSize, id))
        assertTrue("expected success, got $outcome", outcome is InstallOutcome.Success)
        val success = outcome as InstallOutcome.Success
        assertEquals(id, success.installId)
        val dir = File(runtimeDir(), id)
        // NOFOLLOW: rootfs entries are symlinks whose absolute targets only
        // resolve inside the PRoot chroot, not on the host — the structural
        // question is "the entry exists in the tree".
        val expected =
            listOf(
                "manifest.json",
                "bin/proot",
                "bin/loader",
                "bin/lib/libtalloc.so.2",
                "bin/lib/libandroid-shmem.so",
                "rootfs/bin/sh",
                "rootfs/bin/bash",
                "rootfs/usr/bin/git",
                "rootfs/usr/bin/python3",
                "rootfs/usr/bin/node",
                "rootfs/usr/bin/rg",
                "home",
                "tmp",
            )
        for (p in expected) {
            assertTrue(
                "missing $p",
                java.nio.file.Files
                    .exists(dir.toPath().resolve(p), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            )
        }
        // The smoke actually scanned the real tree on the real page size.
        assertEquals(SmokeStatus.PASSED, success.smoke.status)
        assertTrue(success.smoke.failures.isEmpty())
        assertTrue("expected >1000 real members, got ${success.members}", success.members > 1000)
        // Manifest parses through the strict codec; the lock fingerprint matches.
        val manifest = RuntimeManifestCodec.parseManifest(File(dir, "manifest.json").readText())
        assertEquals(id, manifest.installId)
        assertEquals(RuntimeLockCodec.sha256Hex(lock), manifest.lockSha256)
        assertEquals(SmokeStatus.PASSED, manifest.smoke?.status)
        // Activation pointers
        assertEquals(id, RootFsInstaller.currentActive(runtimeDir())?.installId)
        assertEquals(null, RootFsInstaller.currentRollback(runtimeDir()))
        assertFalse(File(File(runtimeDir(), "state"), "rollback.json").exists())
        // runtime/ contains exactly state + the version dir
        assertEquals(
            listOf(id, "state"),
            runtimeDir()
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun anUpdateKeepsOneRollback() {
        val lock = installedLock()
        val pageSize = pageSizeBytes()
        val a = "inst_" + "aa".repeat(6)
        val b = "inst_" + "bb".repeat(6)
        assertTrue(RootFsInstaller.install(defaultRequest(lock, pageSize, a)) is InstallOutcome.Success)
        assertTrue(RootFsInstaller.install(defaultRequest(lock, pageSize, b)) is InstallOutcome.Success)
        assertEquals(b, RootFsInstaller.currentActive(runtimeDir())?.installId)
        assertEquals(a, RootFsInstaller.currentRollback(runtimeDir())?.installId)
        // rollback activation swaps the pointers without moving files
        val done = RootFsInstaller.activateRollback(runtimeDir(), System.currentTimeMillis())
        assertTrue("expected Done, got $done", done is RollbackOutcome.Done)
        assertEquals(a, (done as RollbackOutcome.Done).newActiveId)
        assertEquals(b, done.newRollbackId)
        assertEquals(a, RootFsInstaller.currentActive(runtimeDir())?.installId)
        assertEquals(b, RootFsInstaller.currentRollback(runtimeDir())?.installId)
    }

    @Test
    fun aTamperedArchiveFailsClosedAndLeavesStateIntact() {
        val lock = installedLock()
        val pageSize = pageSizeBytes()
        val first = "inst_" + "aa".repeat(6)
        assertTrue(RootFsInstaller.install(defaultRequest(lock, pageSize, first)) is InstallOutcome.Success)
        // flip exactly one byte at offset 4096 (inside a later member header)
        val outcome =
            RootFsInstaller.install(
                corruptedRequest(lock, pageSize, "inst_" + "bb".repeat(6), atOffset = 4096),
            )
        assertTrue("expected failure, got $outcome", outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
        // prior install fully intact, no partials
        assertEquals(first, RootFsInstaller.currentActive(runtimeDir())?.installId)
        val children =
            runtimeDir()
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(listOf(first, "state"), children)
    }

    @Test
    fun orphansAndStalePartialsAreSweptAtInstallStart() {
        val lock = installedLock()
        val pageSize = pageSizeBytes()
        val root = runtimeDir()
        File(root, "inst_" + "ff".repeat(6)).mkdirs()
        File(root, ".partial-crash").mkdirs()
        val id = "inst_" + "cc".repeat(6)
        val outcome = RootFsInstaller.install(defaultRequest(lock, pageSize, id))
        assertTrue("expected success, got $outcome", outcome is InstallOutcome.Success)
        val swept = (outcome as InstallOutcome.Success).swept.sorted()
        assertEquals(listOf(".partial-crash", "inst_" + "ff".repeat(6)), swept)
        assertEquals(
            listOf(id, "state"),
            root
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun theEmbeddedArchiveIsReadableByTheStrictTarReader() {
        // Fail-closed structural gate: the real 2692-member archive must fully
        // scan (types, paths, symlink targets) on-device before any install.
        val lock = installedLock()
        val name = ProotRuntimeInstaller.embeddedArchiveName(lock)
        val members = context.assets.open("runtime/rootfs/$name").use { TarStream.scan(it) }
        assertEquals(2692, members.size)
    }
}
