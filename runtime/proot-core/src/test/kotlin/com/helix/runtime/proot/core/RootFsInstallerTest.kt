package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class RootFsInstallerTest {
    @Rule
    @JvmField
    var tmp = TemporaryFolder()

    // --- fakes ------------------------------------------------------------------

    private val elf = SyntheticElf.elf64Le()
    private val fakeSha = "ab".repeat(32)

    private fun tarBytes(): ByteArray =
        SyntheticTar.archive(
            SyntheticTar.Entry("etc/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("bin/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("bin/sh", content = "#!/bin/sh\n", mode = 0x1ED),
            SyntheticTar.Entry("bin/bash", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("bin/busybox", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("lib/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("lib/ld-musl-aarch64.so.1", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("usr/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/git", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/python3", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/node", content = elf, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/rg", content = elf, mode = 0x1ED),
            // An absolute target inside the tree (PRoot chroot semantics) and a
            // dangling runtime path: both must be legal, never host-resolved.
            SyntheticTar.Entry("etc/mtab", type = '2', linkTarget = "/proc/self/mounts"),
            SyntheticTar.Entry("usr/bin/rg.bak", type = '2', linkTarget = "rg"),
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun lockJson(archive: ByteArray): String =
        """
        {
          "lockVersion": 1,
          "abi": "arm64-v8a",
          "components": [
            {
              "id": "proot",
              "version": "5.1.107.92",
              "abi": "arm64-v8a",
              "url": "https://example.com/proot.deb",
              "size": 1,
              "sha256": "$fakeSha",
              "license": {
                "spdx": "GPL-2.0-or-later",
                "name": "PRoot",
                "textRef": "licenses/GPL-2.0-or-later.txt"
              },
              "source": {
                "repository": "https://github.com/termux/proot",
                "ref": "v5.1.107.92",
                "patches": []
              },
              "packages": []
            },
            {
              "id": "alpine-rootfs",
              "version": "3.22.5",
              "abi": "arm64-v8a",
              "url": "https://example.com/alpine-rootfs.tar",
              "size": ${archive.size},
              "sha256": "${sha256Hex(archive)}",
              "license": {
                "spdx": "NOASSERTION",
                "name": "Alpine Linux rootfs",
                "textRef": "licenses/ALPINE-README.md"
              },
              "source": {
                "repository": "https://dl-cdn.alpinelinux.org/alpine/v3.22",
                "ref": "releases/aarch64",
                "patches": []
              },
              "packages": [
                {"name": "bash", "version": "5.2.37-r0", "licenseSpdx": "GPL-3.0-or-later"},
                {"name": "git", "version": "2.49.1-r0", "licenseSpdx": "GPL-2.0-or-later"},
                {"name": "python3", "version": "3.12.14-r0", "licenseSpdx": "PSF-2.0"},
                {"name": "nodejs", "version": "22.23.2-r0", "licenseSpdx": "MIT"},
                {"name": "ripgrep", "version": "14.1.1-r0", "licenseSpdx": "(MIT OR Apache-2.0) AND Unicode-3.0"}
              ]
            }
          ]
        }
        """.trimIndent()

    private fun request(
        root: File,
        archive: ByteArray = tarBytes(),
        pageSize: Long = 4096L,
        installId: String = RootFsInstaller.newInstallId(),
        lockArchive: ByteArray = archive,
    ): InstallRequest {
        val lock = RuntimeLockCodec.parse(lockJson(lockArchive)).requireBaseline()
        return InstallRequest(
            runtimeRoot = root,
            lock = lock,
            rootfsArchiveStream = { ByteArrayInputStream(archive) },
            prootAssets =
                listOf(
                    ProotAssetSource("proot", { ByteArrayInputStream(elf) }),
                    ProotAssetSource("loader", { ByteArrayInputStream(elf) }),
                    ProotAssetSource("lib/libtalloc.so.2", { ByteArrayInputStream(elf) }),
                ),
            facts =
                InstallFacts(
                    pageSizeBytes = pageSize,
                    nowEpochMs = 1_767_225_600_000L,
                    installIdGenerator = { installId },
                ),
        )
    }

    private fun rootChildren(root: File): List<String> =
        root
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    // --- tests --------------------------------------------------------------------

    @Test
    fun aFreshInstallPromotesVerifiesAndActivates() {
        val root = tmp.newFolder("runtime")
        val req = request(root)
        val outcome = RootFsInstaller.install(req)
        assertTrue("expected success, got $outcome", outcome is InstallOutcome.Success)
        val success = outcome as InstallOutcome.Success
        val dir = File(root, success.installId)
        for (p in listOf(
            "manifest.json",
            "bin/proot",
            "bin/loader",
            "bin/lib/libtalloc.so.2",
            "rootfs/bin/sh",
            "rootfs/usr/bin/rg",
            "home",
            "tmp",
        )) {
            assertTrue("missing $p", File(dir, p).exists())
        }
        // symlink survived with its stored target (never host-resolved)
        val mtab = Files.readSymbolicLink(dir.toPath().resolve("rootfs/etc/mtab"))
        assertEquals("/proc/self/mounts", mtab.toString())
        // manifest parses through the strict codec; smoke + lock fingerprint recorded
        val manifest = RuntimeManifestCodec.parseManifest(File(dir, "manifest.json").readText())
        assertEquals(success.installId, manifest.installId)
        assertEquals(RuntimeLockCodec.sha256Hex(req.lock), manifest.lockSha256)
        assertEquals(SmokeStatus.PASSED, manifest.smoke?.status)
        assertTrue(manifest.smoke?.failures?.isEmpty() == true)
        // activation: active set, no rollback on a first install
        assertEquals(success.installId, RootFsInstaller.currentActive(root)?.installId)
        assertEquals(null, RootFsInstaller.currentRollback(root))
        assertFalse(File(File(root, "state"), "rollback.json").exists())
        // no partials left; only state + the version dir
        assertEquals(listOf(success.installId, "state"), rootChildren(root))
    }

    @Test
    fun symlinksAtRequiredRootfsPathsPassTheStructuralSmoke() {
        // The real rootfs multiplexes through symlinks (busybox layout):
        // bin/sh -> /bin/busybox, usr/bin/python3 -> python3.12, ... Absolute
        // targets are dangling on the host (macOS/Android) but must not fail the
        // structural check — under PRoot they resolve inside the chroot.
        val archive =
            SyntheticTar.archive(
                SyntheticTar.Entry("etc/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("bin/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("bin/busybox", content = elf, mode = 0x1ED),
                SyntheticTar.Entry("bin/sh", type = '2', linkTarget = "/bin/busybox"),
                SyntheticTar.Entry("bin/bash", type = '2', linkTarget = "busybox"),
                SyntheticTar.Entry("lib/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("lib/ld-musl-aarch64.so.1", content = elf, mode = 0x1ED),
                SyntheticTar.Entry("usr/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("usr/bin/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("usr/libexec/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("usr/libexec/git-core/", type = '5', mode = 0x1ED),
                SyntheticTar.Entry("usr/libexec/git-core/git", content = elf, mode = 0x1ED),
                SyntheticTar.Entry("usr/bin/git", type = '2', linkTarget = "/usr/libexec/git-core/git"),
                SyntheticTar.Entry("usr/bin/python3.12", content = elf, mode = 0x1ED),
                SyntheticTar.Entry("usr/bin/python3", type = '2', linkTarget = "python3.12"),
                SyntheticTar.Entry("usr/bin/node", content = elf, mode = 0x1ED),
                SyntheticTar.Entry("usr/bin/rg", content = elf, mode = 0x1ED),
            )
        val root = tmp.newFolder("symlink-smoke")
        val outcome = RootFsInstaller.install(request(root, archive = archive, lockArchive = archive))
        assertTrue("expected success, got $outcome", outcome is InstallOutcome.Success)
        assertEquals(SmokeStatus.PASSED, (outcome as InstallOutcome.Success).smoke.status)
    }

    @Test
    fun anUpdateKeepsOneRollbackAndSweepsOlderVersions() {
        val root = tmp.newFolder("runtime")
        val a = "inst_" + "aa".repeat(6)
        val b = "inst_" + "bb".repeat(6)
        val c = "inst_" + "cc".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = a)) is InstallOutcome.Success)
        assertTrue(RootFsInstaller.install(request(root, installId = b)) is InstallOutcome.Success)
        val outcomeC = RootFsInstaller.install(request(root, installId = c))
        assertTrue(outcomeC is InstallOutcome.Success)
        assertEquals(c, RootFsInstaller.currentActive(root)?.installId)
        assertEquals(b, RootFsInstaller.currentRollback(root)?.installId)
        // exactly two version directories remain: the old active kept, the older swept
        val versions =
            root
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("inst_") }
                .map { it.name }
                .sorted()
        assertEquals(listOf(b, c), versions)
        // a stays until AFTER the activation writes, then is deleted by the old-rollback
        // cleanup (it is still referenced by rollback.json at sweep time, so not "swept").
        assertEquals(emptyList<String>(), (outcomeC as InstallOutcome.Success).swept)
    }

    @Test
    fun aHashMismatchFailsBeforePromotionAndLeavesStateUntouched() {
        val root = tmp.newFolder("runtime")
        val first = "inst_" + "aa".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = first)) is InstallOutcome.Success)
        // second install with one tampered archive byte
        val bad = tarBytes().copyOf()
        bad[700] = (bad[700].toInt() xor 0xFF).toByte()
        val outcome = RootFsInstaller.install(request(root, archive = bad, installId = "inst_" + "bb".repeat(6)))
        assertTrue("expected failure, got $outcome", outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
        // prior state fully intact, no partials
        assertEquals(first, RootFsInstaller.currentActive(root)?.installId)
        assertFalse(rootChildren(root).any { it.startsWith(".partial-") })
        assertEquals(listOf(first, "state"), rootChildren(root))
        // and the system recovers: a good install succeeds afterwards
        val third = "inst_" + "cc".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = third)) is InstallOutcome.Success)
        assertEquals(third, RootFsInstaller.currentActive(root)?.installId)
        assertEquals(first, RootFsInstaller.currentRollback(root)?.installId)
    }

    @Test
    fun anArchiveLongerThanTheLockFailsClosed() {
        val root = tmp.newFolder("runtime")
        val full = tarBytes()
        val extra = full + "garbage".encodeToByteArray()
        val outcome = RootFsInstaller.install(request(root, archive = extra, lockArchive = full))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
    }

    @Test
    fun aTruncatedArchiveFailsClosed() {
        val root = tmp.newFolder("runtime")
        val full = tarBytes()
        // The lock describes the full archive; the stream dies after one header block.
        val outcome = RootFsInstaller.install(request(root, archive = full.copyOf(512), lockArchive = full))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
    }

    @Test
    fun aZipSlipMemberPathIsRejected() {
        val root = tmp.newFolder("runtime")
        val evil =
            SyntheticTar.archive(
                SyntheticTar.Entry("bin/", type = '5'),
                SyntheticTar.Entry("../evil", content = "x"),
            )
        val outcome = RootFsInstaller.install(request(root, archive = evil))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
        assertFalse(File(root.parentFile, "evil").exists())
    }

    @Test
    fun aSymlinkEscapeIsRejected() {
        val root = tmp.newFolder("runtime")
        val evil =
            SyntheticTar.archive(
                SyntheticTar.Entry("etc/", type = '5'),
                SyntheticTar.Entry("etc/pwn", type = '2', linkTarget = "../../evil"),
            )
        val outcome = RootFsInstaller.install(request(root, archive = evil))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
    }

    @Test
    fun anInTreeDottedSymlinkIsAccepted() {
        // /etc/os-release -> ../usr/lib/os-release is the real Alpine shape: legal.
        val root = tmp.newFolder("runtime")
        val archive =
            rootfsArchive(
                elf,
                SyntheticTar.Entry("usr/lib/", type = '5'),
                SyntheticTar.Entry("usr/lib/os-release", content = "id=alpine\n"),
                SyntheticTar.Entry("etc/os-release", type = '2', linkTarget = "../usr/lib/os-release"),
            )
        val outcome = RootFsInstaller.install(request(root, archive = archive))
        assertTrue("expected success, got $outcome", outcome is InstallOutcome.Success)
        val id = (outcome as InstallOutcome.Success).installId
        val link =
            Files.readSymbolicLink(
                File(root, id).toPath().resolve("rootfs/etc/os-release"),
            )
        assertEquals("../usr/lib/os-release", link.toString())
        // and it resolves to content inside the tree
        val resolved = File(File(root, id), "rootfs/etc/os-release").toPath().toRealPath()
        assertEquals("id=alpine\n", Files.readString(resolved))
    }

    @Test
    fun aForbiddenMemberTypeIsRejected() {
        val root = tmp.newFolder("runtime")
        val evil = SyntheticTar.archive(SyntheticTar.forbidden("dev/null", type = '3'))
        val outcome = RootFsInstaller.install(request(root, archive = evil))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("archive", (outcome as InstallOutcome.Failure).stage)
    }

    private fun rootfsArchive(
        binary: ByteArray,
        vararg extra: SyntheticTar.Entry,
    ): ByteArray =
        SyntheticTar.archive(
            SyntheticTar.Entry("etc/", type = '5'),
            SyntheticTar.Entry("bin/", type = '5'),
            SyntheticTar.Entry("bin/sh", content = "#!/bin/sh\n"),
            SyntheticTar.Entry("bin/bash", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("bin/busybox", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("lib/", type = '5'),
            SyntheticTar.Entry("lib/ld-musl-aarch64.so.1", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("usr/", type = '5'),
            SyntheticTar.Entry("usr/bin/", type = '5'),
            SyntheticTar.Entry("usr/bin/git", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/python3", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/node", content = binary, mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/rg", content = binary, mode = 0x1ED),
            *extra,
        )

    @Test
    fun theElfPreCheckBlocksMisalignmentAtTheDevicePageSize() {
        val misaligned = SyntheticElf.elf64Le(pAlign = 0x1000L)
        val archive = rootfsArchive(misaligned)
        // 16 KiB page: 4 KiB-aligned ELFs must be blocked pre-activation
        val root = tmp.newFolder("runtime")
        val blocked = RootFsInstaller.install(request(root, archive = archive, pageSize = 16_384L))
        assertTrue(blocked is InstallOutcome.Failure)
        assertEquals("smoke", (blocked as InstallOutcome.Failure).stage)
        assertTrue(blocked.reason.contains("page alignment"))
        assertEquals(null, RootFsInstaller.currentActive(root))
        // the same archive passes on a 4 KiB device
        val root2 = tmp.newFolder("runtime2")
        assertTrue(
            RootFsInstaller.install(request(root2, archive = archive, pageSize = 4096L)) is InstallOutcome.Success,
        )
    }

    @Test
    fun theElfPreCheckBlocksAnAbiMismatch() {
        val x64 = SyntheticElf.elf64Le(machine = 62)
        val root = tmp.newFolder("runtime")
        val outcome = RootFsInstaller.install(request(root, archive = rootfsArchive(x64)))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("smoke", (outcome as InstallOutcome.Failure).stage)
        assertTrue(outcome.reason.contains("ABI mismatch"))
    }

    @Test
    fun theElfPreCheckRejectsAnUnverifiableProgramHeaderTable() {
        // An ELF whose e_phoff points past the smoke's bounded read prefix (64 KiB)
        // cannot have its PT_LOAD alignment verified — it must fail closed rather
        // than be skipped. The bound exists so a large member (51 MB in the
        // pinned rootfs) is never buffered whole in a memory-limited process.
        val phoffBeyondPrefix = SyntheticElf.elf64Le().copyOf()
        // NOTE: ByteBuffer.wrap(arr, off, len) wraps the WHOLE array at position
        // off (not a slice) — use a full-array wrap with an absolute put.
        java.nio.ByteBuffer
            .wrap(phoffBeyondPrefix)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(0x20, 0x0100_0000L)
        val root = tmp.newFolder("runtime")
        val outcome = RootFsInstaller.install(request(root, archive = rootfsArchive(phoffBeyondPrefix)))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("smoke", (outcome as InstallOutcome.Failure).stage)
        assertTrue(outcome.reason.contains("e_phoff out of bounds"))
    }

    @Test
    fun aCorruptActivationFileFailsClosed() {
        val root = tmp.newFolder("runtime")
        val first = "inst_" + "aa".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = first)) is InstallOutcome.Success)
        File(File(root, "state"), "active.json").writeText("{ not json")
        val outcome = RootFsInstaller.install(request(root, installId = "inst_" + "bb".repeat(6)))
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("state", (outcome as InstallOutcome.Failure).stage)
    }

    @Test
    fun stalePartialsAndOrphansAreSwept() {
        val root = tmp.newFolder("runtime")
        File(root, "inst_" + "dd".repeat(6)).mkdirs() // orphan version
        File(root, ".partial-junk").mkdirs() // crashed install remnant
        val id = "inst_" + "ee".repeat(6)
        val outcome = RootFsInstaller.install(request(root, installId = id))
        assertTrue(outcome is InstallOutcome.Success)
        val swept = (outcome as InstallOutcome.Success).swept.sorted()
        assertEquals(listOf(".partial-junk", "inst_" + "dd".repeat(6)), swept)
        assertEquals(listOf(id, "state"), rootChildren(root))
    }

    @Test
    fun rollbackActivationSwapsThePointers() {
        val root = tmp.newFolder("runtime")
        val a = "inst_" + "aa".repeat(6)
        val b = "inst_" + "bb".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = a)) is InstallOutcome.Success)
        assertTrue(RootFsInstaller.install(request(root, installId = b)) is InstallOutcome.Success)
        val done = RootFsInstaller.activateRollback(root, 1_767_225_700_000L)
        assertTrue("expected Done, got $done", done is RollbackOutcome.Done)
        assertEquals(a, (done as RollbackOutcome.Done).newActiveId)
        assertEquals(b, done.newRollbackId)
        assertEquals(a, RootFsInstaller.currentActive(root)?.installId)
        assertEquals(b, RootFsInstaller.currentRollback(root)?.installId)
        // both version directories still exist (rollback needs its files)
        assertTrue(File(root, a).isDirectory)
        assertTrue(File(root, b).isDirectory)
        // swapping again restores the forward state
        val back = RootFsInstaller.activateRollback(root, 1_767_225_800_000L)
        assertTrue(back is RollbackOutcome.Done)
        assertEquals(b, (back as RollbackOutcome.Done).newActiveId)
        assertEquals(a, back.newRollbackId)
    }

    @Test
    fun rollbackWithoutStateReportsStably() {
        val empty = tmp.newFolder("empty")
        assertEquals(RollbackOutcome.NoActive, RootFsInstaller.activateRollback(empty, 0L))
        val root = tmp.newFolder("runtime")
        val a = "inst_" + "aa".repeat(6)
        assertTrue(RootFsInstaller.install(request(root, installId = a)) is InstallOutcome.Success)
        assertEquals(RollbackOutcome.NoRollback, RootFsInstaller.activateRollback(root, 0L))
    }

    @Test
    fun aContendedLockFailsFast() {
        val root = tmp.newFolder("runtime")
        val lockFile = File(File(root, "state"), "install.lock")
        lockFile.parentFile!!.mkdirs()
        lockFile.createNewFile()
        // Hold the channel across the install: closing it releases the lock.
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE)
        channel.lock()
        val outcome =
            try {
                RootFsInstaller.install(request(root))
            } finally {
                channel.close()
            }
        assertTrue(outcome is InstallOutcome.Failure)
        assertEquals("lock", (outcome as InstallOutcome.Failure).stage)
    }
}
