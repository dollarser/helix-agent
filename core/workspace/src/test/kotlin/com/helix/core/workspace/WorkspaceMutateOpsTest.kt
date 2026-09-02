package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HXA-043: the copy / move / trash store seams behind `files.copy` / `files.move` /
 * `files.delete` — explicit conflict policy (an existing destination, file OR directory, is
 * refused without an explicit overwrite; an overwrite into a directory is always refused),
 * cross-scope operations (destination-scope quota gated; a cross-scope move never loses its
 * source), and the trash: delete is a rename into `.helix/trash/` with a reversible entry name,
 * restore and physical purge are SEPARATE operations, and neither can touch a non-trash path.
 *
 * All cases run against JVM temp dirs (verification matrix HXA-043 device: 无).
 */
class WorkspaceMutateOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun resolver(scopeRoot: Path) = ScopeRootResolver { _ -> scopeRoot }

    private fun twoScopeResolver(
        a: Path,
        b: Path,
    ) = ScopeRootResolver { id -> if (id == "ws") a else b }

    private fun store(root: Path): WorkspaceArtifactStore = WorkspaceArtifactStore(resolver(root))

    private fun ref(
        relative: String,
        scope: String = "ws",
    ): FileScopePath = FileScopePath(scope, relative)

    private fun freshScope(name: String): Path {
        val p = tmp.newFolder(name).toPath()
        WorkspaceArtifactStore(ScopeRootResolver { _ -> p }).ensureLayout("ws")
        return p
    }

    private fun target(
        root: Path,
        rel: String,
    ): Path = root.resolve(rel)

    private fun write(
        root: Path,
        rel: String,
        content: String,
    ): Path {
        val p = target(root, rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.write(p, content.toByteArray())
        return p
    }

    // ── copy ────────────────────────────────────────────────────────────────────────────

    @Test
    fun copySameScopeCopiesBytesAndKeepsTheSource() {
        val root = freshScope("c1")
        write(root, "work/a.txt", "payload")
        val out = store(root).copyFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, false)
        assertEquals("payload", String(Files.readAllBytes(target(root, "output/b.txt"))))
        assertEquals("the source survives a copy", "payload", String(Files.readAllBytes(target(root, "work/a.txt"))))
        assertEquals("output/b.txt", out.destinationRelativePath)
        assertEquals(7L, out.sizeBytes)
        assertEquals(hex("payload".toByteArray()), out.sha256)
        assertFalse(out.overwritten)
        assertEquals(store(root).usageBytes("ws"), out.usageBytesAfter)
    }

    @Test
    fun copyCrossScopePublishesIntoTheDestinationScope() {
        val a = freshScope("c2a")
        val b = freshScope("c2b")
        val s = WorkspaceArtifactStore(twoScopeResolver(a, b))
        write(a, "work/a.txt", "cross")
        val out =
            s.copyFile(
                ref("work/a.txt", scope = "ws"),
                ref("output/c.txt", scope = "other"),
                WorkspaceLayout.OUTPUT,
                false,
            )
        assertEquals("cross", String(Files.readAllBytes(target(b, "output/c.txt"))))
        assertTrue("the source stays in its own scope", Files.exists(target(a, "work/a.txt")))
        assertEquals(s.usageBytes("other"), out.usageBytesAfter)
    }

    @Test
    fun copyRefusesAnExistingDestinationWithoutOverwriteAndLeavesIt() {
        val root = freshScope("c3")
        write(root, "work/a.txt", "src")
        write(root, "output/b.txt", "keep")
        assertThrows(FileAlreadyExistsException::class.java) {
            store(root).copyFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, false)
        }
        assertEquals("the destination is untouched", "keep", String(Files.readAllBytes(target(root, "output/b.txt"))))
    }

    @Test
    fun copyWithOverwriteReplacesTheDestinationAndReportsOverwritten() {
        val root = freshScope("c4")
        write(root, "work/a.txt", "src")
        write(root, "output/b.txt", "old")
        val out = store(root).copyFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, true)
        assertEquals("src", String(Files.readAllBytes(target(root, "output/b.txt"))))
        assertTrue(out.overwritten)
    }

    @Test
    fun copyRefusesADirectoryDestinationEvenWithOverwrite() {
        val root = freshScope("c5")
        write(root, "work/a.txt", "src")
        Files.createDirectories(target(root, "output/dir"))
        assertThrows(FileAlreadyExistsException::class.java) {
            store(root).copyFile(ref("work/a.txt"), ref("output/dir"), WorkspaceLayout.OUTPUT, true)
        }
        assertTrue(Files.isDirectory(target(root, "output/dir")))
    }

    @Test
    fun copyOfAMissingSourceFailsClosed() {
        val root = freshScope("c6")
        assertThrows(FileNotFoundException::class.java) {
            store(root).copyFile(ref("work/nope.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, false)
        }
        assertFalse(Files.exists(target(root, "output/b.txt")))
    }

    @Test
    fun copyEnforcesTheDestinationRegion() {
        val root = freshScope("c7")
        write(root, "work/a.txt", "src")
        assertThrows(IllegalArgumentException::class.java) {
            store(root).copyFile(ref("work/a.txt"), ref(".helix/metadata.json"), WorkspaceLayout.HELIX, false)
        }
    }

    @Test
    fun aQuotaExceedingCopyIsRefusedBeforeAnythingIsWritten() {
        val a = freshScope("c8a")
        val b = tmp.newFolder("c8b").toPath()
        val tiny = WorkspaceArtifactStore(twoScopeResolver(a, b), WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("other")
        write(a, "work/big.txt", "x".repeat(32))
        assertThrows(WorkspaceQuota.QuotaExceeded::class.java) {
            tiny.copyFile(
                ref("work/big.txt", scope = "ws"),
                ref("output/big.txt", scope = "other"),
                WorkspaceLayout.OUTPUT,
                false,
            )
        }
        assertFalse(Files.exists(target(b, "output/big.txt")))
        assertTrue("the source is never touched by a refused copy", Files.exists(target(a, "work/big.txt")))
    }

    // ── move ────────────────────────────────────────────────────────────────────────────

    @Test
    fun moveSameScopeMovesTheFileAndRemovesTheSource() {
        val root = freshScope("m1")
        write(root, "work/a.txt", "payload")
        val out = store(root).moveFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, false)
        assertFalse(Files.exists(target(root, "work/a.txt")))
        assertEquals("payload", String(Files.readAllBytes(target(root, "output/b.txt"))))
        assertEquals(hex("payload".toByteArray()), out.sha256)
        assertFalse(out.overwritten)
    }

    @Test
    fun moveCrossScopeCopiesIntoTheDestinationThenRemovesTheSource() {
        val a = freshScope("m2a")
        val b = freshScope("m2b")
        val s = WorkspaceArtifactStore(twoScopeResolver(a, b))
        write(a, "work/a.txt", "cross")
        s.moveFile(ref("work/a.txt", scope = "ws"), ref("output/c.txt", scope = "other"), WorkspaceLayout.OUTPUT, false)
        assertEquals("cross", String(Files.readAllBytes(target(b, "output/c.txt"))))
        val srcPath = target(a, "work/a.txt")
        assertFalse("the source is deleted only after the destination is published", Files.exists(srcPath))
    }

    @Test
    fun aQuotaExceedingCrossScopeMoveLeavesTheSourceIntact() {
        val a = freshScope("m3a")
        val b = tmp.newFolder("m3b").toPath()
        val tiny = WorkspaceArtifactStore(twoScopeResolver(a, b), WorkspaceQuotaPolicy(16L))
        tiny.ensureLayout("other")
        write(a, "work/big.txt", "x".repeat(32))
        assertThrows(WorkspaceQuota.QuotaExceeded::class.java) {
            tiny.moveFile(
                ref("work/big.txt", scope = "ws"),
                ref("output/big.txt", scope = "other"),
                WorkspaceLayout.OUTPUT,
                false,
            )
        }
        assertEquals(
            "a refused move must not lose the source",
            "x".repeat(32),
            String(Files.readAllBytes(target(a, "work/big.txt"))),
        )
        assertFalse(Files.exists(target(b, "output/big.txt")))
    }

    @Test
    fun moveRefusesAnExistingDestinationWithoutOverwriteAndOverwriteReplaces() {
        val root = freshScope("m4")
        write(root, "work/a.txt", "src")
        write(root, "output/b.txt", "keep")
        assertThrows(FileAlreadyExistsException::class.java) {
            store(root).moveFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, false)
        }
        assertEquals("keep", String(Files.readAllBytes(target(root, "output/b.txt"))))
        assertTrue("the source survives a refused move", Files.exists(target(root, "work/a.txt")))
        val out = store(root).moveFile(ref("work/a.txt"), ref("output/b.txt"), WorkspaceLayout.OUTPUT, true)
        assertEquals("src", String(Files.readAllBytes(target(root, "output/b.txt"))))
        assertTrue(out.overwritten)
        assertFalse(Files.exists(target(root, "work/a.txt")))
    }

    // ── trash: delete / restore / purge are separate, reversible operations ─────────────

    private fun trashRefOf(entry: TrashEntry): FileScopePath = ref(WorkspaceLayout.TRASH + "/" + entry.trashName)

    @Test
    fun moveToTrashParksTheFileWithAReversibleNameAndFreesTheOriginalPath() {
        val root = freshScope("t1")
        write(root, "work/sub/a.txt", "payload")
        val entry = store(root).moveToTrash(ref("work/sub/a.txt"))
        assertEquals("work/sub/a.txt", entry.originalRelativePath)
        assertTrue(WorkspaceArtifactStore.TRASH_ENTRY_NAME.matches(entry.trashName))
        assertEquals(7L, entry.sizeBytes)
        assertEquals(hex("payload".toByteArray()), entry.sha256)
        assertFalse("the original path is gone", Files.exists(target(root, "work/sub/a.txt")))
        assertEquals("payload", String(Files.readAllBytes(target(root, WorkspaceLayout.TRASH + "/" + entry.trashName))))
    }

    @Test
    fun trashNamesRoundTripPathsContainingPercentAndSeparators() {
        val root = freshScope("t2")
        val s = store(root)
        val tricky1 = write(root, "work/a%2Fb.txt", "one")
        val tricky2 = write(root, "work/100%/x y.txt", "two")
        val e1 = s.moveToTrash(ref("work/a%2Fb.txt"))
        val e2 = s.moveToTrash(ref("work/100%/x y.txt"))
        assertTrue("distinct originals must yield distinct entries", e1.trashName != e2.trashName)
        s.restoreFromTrash(trashRefOf(e1))
        s.restoreFromTrash(trashRefOf(e2))
        assertEquals("one", String(Files.readAllBytes(tricky1)))
        assertEquals("two", String(Files.readAllBytes(tricky2)))
    }

    @Test
    fun restoreFromTrashReturnsTheFileToItsOriginalPath() {
        val root = freshScope("t3")
        val s = store(root)
        write(root, "work/a.txt", "payload")
        val entry = s.moveToTrash(ref("work/a.txt"))
        val out = s.restoreFromTrash(trashRefOf(entry))
        val trashEntry = target(root, WorkspaceLayout.TRASH + "/" + entry.trashName)
        assertEquals("work/a.txt", out.restoredRelativePath)
        assertEquals("payload", String(Files.readAllBytes(target(root, "work/a.txt"))))
        assertFalse("the entry is consumed by the restore", Files.exists(trashEntry))
        assertEquals(s.usageBytes("ws"), out.usageBytesAfter)
    }

    @Test
    fun restoreRefusesWhenTheOriginalPathIsOccupiedAndKeepsTheEntry() {
        val root = freshScope("t4")
        val s = store(root)
        write(root, "work/a.txt", "original")
        val entry = s.moveToTrash(ref("work/a.txt"))
        write(root, "work/a.txt", "new occupant")
        assertThrows(FileAlreadyExistsException::class.java) { s.restoreFromTrash(trashRefOf(entry)) }
        assertTrue("the occupant survives", "new occupant" == String(Files.readAllBytes(target(root, "work/a.txt"))))
        val trashEntry = target(root, WorkspaceLayout.TRASH + "/" + entry.trashName)
        assertTrue("the entry stays in the trash", Files.exists(trashEntry))
    }

    @Test
    fun purgeTrashEntryPermanentlyRemovesItAndReclaimsUsage() {
        val root = freshScope("t5")
        val s = store(root)
        write(root, "work/a.txt", "payload")
        val entry = s.moveToTrash(ref("work/a.txt"))
        val before = s.usageBytes("ws")
        val out = s.purgeTrashEntry(trashRefOf(entry))
        assertFalse(Files.exists(target(root, WorkspaceLayout.TRASH + "/" + entry.trashName)))
        assertEquals(before - 7L, s.usageBytes("ws"))
        assertEquals(s.usageBytes("ws"), out.usageBytesAfter)
    }

    @Test
    fun restoreAndPurgeRefuseAnythingThatIsNotATrashEntry() {
        val root = freshScope("t6")
        val s = store(root)
        write(root, "work/a.txt", "payload")
        assertThrows(IllegalArgumentException::class.java) { s.restoreFromTrash(ref("work/a.txt")) }
        assertThrows(IllegalArgumentException::class.java) { s.purgeTrashEntry(ref("work/a.txt")) }
        assertEquals("the file is untouched", "payload", String(Files.readAllBytes(target(root, "work/a.txt"))))
        // A path inside the trash directory but not a well-formed entry name is refused too.
        val foreign = target(root, WorkspaceLayout.TRASH + "/not-an-entry.txt")
        Files.write(foreign, "x".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            s.purgeTrashEntry(ref(WorkspaceLayout.TRASH + "/not-an-entry.txt"))
        }
        assertTrue(Files.exists(foreign))
    }

    @Test
    fun restoreAndPurgeOfAMissingEntryFailClosed() {
        val root = freshScope("t7")
        val s = store(root)
        val missing = ref(WorkspaceLayout.TRASH + "/1234567890123-abcd1234__work%2Fx.txt")
        assertThrows(FileNotFoundException::class.java) { s.restoreFromTrash(missing) }
        assertThrows(FileNotFoundException::class.java) { s.purgeTrashEntry(missing) }
    }

    @Test
    fun deleteToTrashOfADirectoryFailsClosed() {
        val root = freshScope("t8")
        Files.createDirectories(target(root, "work/dir"))
        assertThrows(FileNotFoundException::class.java) { store(root).moveToTrash(ref("work/dir")) }
        assertTrue(Files.isDirectory(target(root, "work/dir")))
    }
}
