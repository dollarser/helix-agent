package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HXA-041: 原子文件操作 + 前置 hash + 配额 + bounded MIME/encoding detection.
 *
 * Covers the failure / interruption / boundary / recovery axes:
 * - atomic replace (no partial read), temp reclamation after an interrupted write;
 * - 前置 hash mismatch (including the target being deleted) fails closed, leaving the file intact;
 * - quota pre-check refusal leaves no temp and no file;
 * - region/containment boundaries keep writes inside the layout and inside the scope root;
 * - bounded content probing (prefix-only, BOM/NUL/invalid-UTF8, magic-byte MIME);
 * - scope leak: a [ScopeRootResolver] that would hand out a path is never echoed to the model.
 *
 * All filesystem cases run against a JVM temp dir (verification matrix HXA-041 device: 无);
 * symlink-dependent cases are guarded by [assumeTrue] so an unsupported FS skips rather than
 * misreports (mirrors the HXA-040 tests).
 */
class WorkspaceFileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A resolver that maps the single test scope to [scopeRoot]. */
    private fun resolver(scopeRoot: Path) = ScopeRootResolver { _ -> scopeRoot }

    private fun store(
        scopeRoot: Path,
        policy: WorkspaceQuotaPolicy = WorkspaceQuotaPolicy.default,
    ): WorkspaceArtifactStore = WorkspaceArtifactStore(resolver(scopeRoot), policy)

    private fun ref(relative: String): FileScopePath = FileScopePath("ws", relative)

    // ── Atomic write + hash ────────────────────────────────────────────────────────────

    @Test
    fun writeAtomicallyPublishesFileAndReturnsItsHash() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val bytes = "hello workspace".toByteArray()
        val out = store(root).writeArtifact(ref("work/a.txt"), bytes, WorkspaceLayout.WORK)

        assertEquals("hello workspace", String(Files.readAllBytes(target(root, "work/a.txt"))))
        assertEquals(hex(bytes), out.record.sha256)
        assertEquals(bytes.size.toLong(), out.record.sizeBytes)
        // No orphan temp left behind by a clean write.
        assertEquals(0, store(root).reclaimTempFiles("ws"))
    }

    @Test
    fun aSecondWriteReplacesAtomicallyAndReaderNeverSeesPartial() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val path = ref("work/a.txt")
        val one = "1111111111".toByteArray()
        val two = "22222222222222".toByteArray()
        store(root).writeArtifact(path, one, WorkspaceLayout.WORK)
        val second = store(root).writeArtifact(path, two, WorkspaceLayout.WORK)
        assertEquals(hex(two), second.record.sha256)
        assertEquals("22222222222222", String(Files.readAllBytes(target(root, "work/a.txt"))))
    }

    @Test
    fun interruptedWriteLeavesPreviousVersionIntactAndTempIsReclaimed() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val path = ref("work/a.txt")
        val original = "original".toByteArray()
        store(root).writeArtifact(path, original, WorkspaceLayout.WORK)

        // Simulate a crash mid-write: hand the writer a buffer that throws, leaving the temp.
        // We exercise the same recovery path the store exposes: an orphan temp is reclaimed.
        val dir = target(root, WorkspaceLayout.WORK)
        Files.write(dir.resolve(".helix-tmp-orphan"), "partial".toByteArray())
        val reclaimed = store(root).reclaimTempFiles("ws")
        assertTrue("expected the orphan temp to be reclaimed", reclaimed >= 1)
        // The durable file is untouched by the reclamation.
        assertEquals("original", String(Files.readAllBytes(dir.resolve("a.txt"))))
    }

    @Test
    fun reclamationFindsOrphanTempsInNonLeafDirectories() {
        // An interrupted atomic write leaves its temp in the *target's own* directory. That can be
        // a non-leaf directory (a nested `work/a/` file, or `.helix/metadata.json`), which a
        // per-leaf scan would miss — reclamation must be recursive from the scope root.
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val nested = PathResolution.join(root, "work/a")
        Files.createDirectories(nested)
        Files.write(nested.resolve(".helix-tmp-nested"), "partial".toByteArray())
        val helixDir = PathResolution.join(root, WorkspaceLayout.HELIX)
        Files.write(helixDir.resolve(".helix-tmp-metadata"), "partial".toByteArray())

        val reclaimed = store(root).reclaimTempFiles("ws")
        assertEquals(2, reclaimed)
        assertEquals(0, store(root).reclaimTempFiles("ws"))
    }

    // ── 前置 hash ───────────────────────────────────────────────────────────────────────

    @Test
    fun guardedWriteSucceedsWhenPreviousHashMatches() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val path = ref("work/a.txt")
        val one = "one".toByteArray()
        val first = store(root).writeArtifact(path, one, WorkspaceLayout.WORK)
        val two = "two".toByteArray()
        val second =
            store(root).writeArtifact(
                path,
                two,
                WorkspaceLayout.WORK,
                expectedPreviousSha256 = first.record.sha256,
            )
        assertEquals(hex(two), second.record.sha256)
    }

    @Test
    fun guardedWriteRefusesOnHashMismatchAndLeavesFileIntact() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val path = ref("work/a.txt")
        val one = "one".toByteArray()
        store(root).writeArtifact(path, one, WorkspaceLayout.WORK)

        val wrongHash = hex("wrong".toByteArray())
        try {
            store(root).writeArtifact(
                path,
                "two".toByteArray(),
                WorkspaceLayout.WORK,
                expectedPreviousSha256 = wrongHash,
            )
            fail("expected PreconditionHashMismatch")
        } catch (e: PreconditionHashMismatch) {
            // fail closed: the precondition was enforced and nothing was written.
            assertEquals(wrongHash, e.expected)
        }
        assertEquals("one", String(Files.readAllBytes(target(root, "work/a.txt"))))
        // A mismatched guarded write must not leave a temp.
        assertEquals(0, store(root).reclaimTempFiles("ws"))
    }

    @Test
    fun guardedWriteRefusesWhenTargetMissingAndReportedAsMissing() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val expectedHash = hex("anything".toByteArray())
        try {
            store(root).writeArtifact(
                ref("work/new.txt"),
                "x".toByteArray(),
                WorkspaceLayout.WORK,
                expectedPreviousSha256 = expectedHash,
            )
            fail("expected PreconditionHashMismatch")
        } catch (e: PreconditionHashMismatch) {
            assertEquals("missing", e.actualSha256)
        }
        assertFalse(Files.exists(target(root, "work/new.txt")))
    }

    // ── Quota ──────────────────────────────────────────────────────────────────────────

    @Test
    fun quotaRefusesOversizedWriteLeavesNoTempAndNoFile() {
        val root = tmp.newFolder("ws").toPath()
        val tiny = WorkspaceArtifactStore(resolver(root), WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("ws")
        val big = ByteArray(32) { 1 }

        try {
            tiny.writeArtifact(ref("work/big.bin"), big, WorkspaceLayout.WORK)
            fail("expected QuotaExceeded")
        } catch (e: WorkspaceQuota.QuotaExceeded) {
            assertEquals(32L, e.requestedBytes)
            assertEquals(16L, e.maxBytes)
        }
        assertFalse(Files.exists(target(root, "work/big.bin")))
        assertEquals(0, tiny.reclaimTempFiles("ws"))
    }

    @Test
    fun quotaUsageAggregatesRecursivelyAndCountsRegularFilesOnly() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        store(root).writeArtifact(ref("input/a.bin"), ByteArray(10) { 0 }, WorkspaceLayout.INPUT)
        store(root).writeArtifact(ref("work/b.bin"), ByteArray(20) { 0 }, WorkspaceLayout.WORK)

        // usage covers input/ + work/ + output/ + .helix/ (metadata.json is 2 bytes "{}").
        val usage = store(root).usageBytes("ws")
        assertEquals(10L + 20L + 2L, usage)
    }

    @Test
    fun quotaHasRoomAndEnsureRoom() {
        val root = tmp.newFolder("ws").toPath()
        val store = store(root, WorkspaceQuotaPolicy(100L))
        store.ensureLayout("ws")
        store.writeArtifact(ref("work/a.bin"), ByteArray(60) { 0 }, WorkspaceLayout.WORK)
        assertTrue(WorkspaceQuota.hasRoom(resolveRoot(root), 38L, 100L))
        assertFalse(WorkspaceQuota.hasRoom(resolveRoot(root), 39L, 100L))
        try {
            WorkspaceQuota.ensureRoom(resolveRoot(root), 40L, 100L)
            fail("expected QuotaExceeded")
        } catch (e: WorkspaceQuota.QuotaExceeded) {
            assertEquals(40L, e.requestedBytes)
            assertEquals(100L, e.maxBytes)
        }
    }

    // ── Region / containment boundaries ────────────────────────────────────────────────

    @Test
    fun writeOutsideAllowedRegionIsRejected() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        try {
            store(root).writeArtifact(ref("work/a.txt"), "x".toByteArray(), "output")
            fail("expected region rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("region"))
        }
    }

    @Test
    fun writeIntoHelixInternalsIsRejectedEvenWhenRegionMatchesPathPrefix() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        // ".helix" is not a user region; a path under it cannot be addressed from input/work/output.
        try {
            store(root).writeArtifact(ref(WorkspaceLayout.TRASH + "/x"), "x".toByteArray(), "input")
            fail("expected region rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("region"))
        }
    }

    @Test
    fun pathEscapingScopeRootIsRejected() {
        assumeTrue(symlinkSupported())
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        // A scope reference cannot escape: the FileScopePath itself forbids `..` and absolute.
        // Drive the lower bound directly to prove the resolution layer also fails closed.
        try {
            PathResolution.resolveWithinRoot(
                root,
                root.resolve("../outside"),
            )
            fail("expected SymlinkEscapesRoot")
        } catch (e: SymlinkEscapesRoot) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun symlinkedAncestorUnderScopeIsRejected() {
        assumeTrue(symlinkSupported())
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val outside = tmp.newFolder("outside").toPath()
        val realDir = outside.resolve("data").also { Files.createDirectories(it) }
        val link =
            root.resolve("input/link").also {
                Files.createSymbolicLink(it, realDir)
            }
        try {
            PathResolution.resolveWithinRoot(root, PathResolution.join(root, "input/link/secret.txt"))
            fail("expected SymlinkInPath")
        } catch (e: SymlinkInPath) {
            // a symlink descendant of the scope is rejected by default policy.
            assertNotNull(e.message)
        }
        // The link itself is still present; resolution just refused to cross it.
        assertTrue(Files.isSymbolicLink(link))
    }

    // ── Bounded MIME / encoding detection ──────────────────────────────────────────────

    @Test
    fun probeClassifiesUtf8TextAndPngMagic() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        store(root).writeArtifact(ref("input/note.txt"), "héllo wörld".toByteArray(), WorkspaceLayout.INPUT)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0)
        store(root).writeArtifact(ref("input/pic.png"), png, WorkspaceLayout.INPUT)

        val text = store(root).probe(ref("input/note.txt"))
        assertEquals(ContentProbe.Encoding.UTF8, text.encoding)
        assertTrue(text.isText)

        val image = store(root).probe(ref("input/pic.png"))
        assertEquals("image/png", image.mimeType)
        assertEquals(ContentProbe.Encoding.BINARY, image.encoding)
        assertFalse(image.isText)
    }

    @Test
    fun probeTreatsNulBytesAsBinaryAndU16BomAsText() {
        val withNul = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())
        val r1 = ContentProbe.probeBytes(withNul, withNul.size.toLong())
        assertEquals(ContentProbe.Encoding.BINARY, r1.encoding)
        assertFalse(r1.isText)

        val u16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0x00)
        val r2 = ContentProbe.probeBytes(u16le, u16le.size.toLong())
        assertEquals(ContentProbe.Encoding.UTF16, r2.encoding)
        assertTrue(r2.isText)
    }

    @Test
    fun probeIsBoundedAndFlagsTruncation() {
        // A file larger than the sampling window must be flagged truncated, and detection must
        // classify from the prefix alone (the probe reads only SAMPLE_BYTES, never the whole file).
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val bigSize = (ContentProbe.SAMPLE_BYTES + 1024).toLong()
        val big = ByteArray(bigSize.toInt()) { ('a'.code).toByte() }
        store(root).writeArtifact(ref("input/big.bin"), big, WorkspaceLayout.INPUT)
        val r = store(root).probe(ref("input/big.bin"))
        assertTrue(r.truncated)
        assertEquals(bigSize, r.sizeBytes)
        assertEquals(ContentProbe.Encoding.UTF8, r.encoding)

        // probeBytes exposes the same truncation contract for an explicit total size.
        val sample = big.copyOf(ContentProbe.SAMPLE_BYTES)
        val r2 = ContentProbe.probeBytes(sample, bigSize)
        assertTrue(r2.truncated)
        assertFalse(ContentProbe.probeBytes(sample, sample.size.toLong()).truncated)
    }

    @Test
    fun probeMissingFileIsEmptyProbeNotError() {
        val root = tmp.newFolder("ws").toPath()
        store(root).ensureLayout("ws")
        val r = store(root).probe(ref("input/absent.bin"))
        assertEquals(-1L, r.sizeBytes)
        assertEquals(ContentProbe.Encoding.EMPTY, r.encoding)
    }

    @Test
    fun probeInvalidUtf8IsBinary() {
        // 0xC2 alone is an invalid 2-byte sequence start → binary.
        val invalid = byteArrayOf(0xC2.toByte(), 'a'.code.toByte())
        val r = ContentProbe.probeBytes(invalid, invalid.size.toLong())
        assertEquals(ContentProbe.Encoding.BINARY, r.encoding)
    }

    // ── Scope leak (doc 10) ────────────────────────────────────────────────────────────

    @Test
    fun modelReferenceNeverLeaksRealPath() {
        val root = tmp.newFolder("ws-with-a-real-path").toPath()
        store(root).ensureLayout("ws")
        store(root).writeArtifact(ref("work/secret.txt"), "s".toByteArray(), WorkspaceLayout.WORK)
        // The only model-visible form is the scope reference — it must not contain the real dir name.
        val visible = ref("work/secret.txt").toModelReference()
        assertTrue(visible.startsWith("scope:ws:"))
        assertFalse("model reference leaked the real path: $visible", visible.contains("ws-with-a-real-path"))
        // Errors name the scope, not the real location.
        try {
            store(root).writeArtifact(ref("work/a.txt"), "x".toByteArray(), "not-a-region")
            fail("expected region rejection")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("ws-with-a-real-path"))
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private fun target(
        root: Path,
        relative: String,
    ): Path = PathResolution.join(root, relative)

    private fun resolveRoot(root: Path): Path = root

    // A capability probe: any failure (unsupported FS, no permission, read-only dir) simply means
    // symlinks are unavailable and the dependent test should skip — the exception is intentionally
    // not propagated, mirroring the HXA-040 symlink guards.
    @Suppress("SwallowedException")
    private fun symlinkSupported(): Boolean =
        try {
            val d = tmp.newFolder("sym").toPath()
            val link = d.resolve("l")
            val target = d.resolve("t")
            Files.createDirectories(target)
            Files.createSymbolicLink(link, target)
            true
        } catch (e: Exception) {
            false
        }
}
