package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/**
 * HXA-046 (文件管理 UI): the user-facing file facade, verified against a real [WorkspaceArtifactStore]
 * on a JVM temp dir (verification matrix: device 无). This exercises the same containment/quota/
 * atomic store the `files.*` tools use, through the browse / sort / preview / mutate / trash /
 * batch shapes the UI drives — so a regression in the facade's region guard, conflict handling, or
 * trash name round-trip is caught on the fast JVM gate.
 *
 * The consumer test classpath carries the no-op [com.helix.app.allfiles.AllFilesModule]
 * (`AVAILABLE == false`), so [FileManagerService.sources] is deterministic here: exactly the
 * workspace. The developer all-files read-only path is covered by the variant-boundary check and
 * the device suite, not this JVM unit.
 */
class FileManagerServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopeId = "ws"
    private lateinit var scopeRoot: Path
    private lateinit var store: WorkspaceArtifactStore
    private lateinit var service: FileManagerService

    @Before
    fun setUp() {
        scopeRoot = tmp.newFolder("ws").toPath()
        val resolver = ScopeRootResolver { _ -> scopeRoot }
        store = WorkspaceArtifactStore(resolver)
        store.ensureLayout(scopeId)
        service = FileManagerService(store, resolver, scopeId)
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun real(relative: String): Path = scopeRoot.resolve(relative)

    /** Seeds a file into the workspace layout (creating parents) via the store's atomic write. */
    private fun seed(
        relative: String,
        bytes: ByteArray,
    ) {
        real(relative).parent?.let { Files.createDirectories(it) }
        store.writeArtifact(FileScopePath(scopeId, relative), bytes, WorkspaceLayout.regionOf(relative)!!)
    }

    private fun seedText(
        relative: String,
        text: String,
    ): Unit = seed(relative, text.toByteArray())

    private fun pngBytes(): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
            0x00,
            0x00,
            0x0D,
            0x49,
            0x48,
            0x44,
            0x52,
            0x00,
        )

    // ── Sources (来源标识) ────────────────────────────────────────────────────────────

    @Test
    fun sourcesListWorkspaceMutableAndNoAllFilesInConsumer() {
        val sources = service.sources()
        assertEquals(1, sources.size)
        val ws = sources.first()
        assertEquals(scopeId, ws.scopeId)
        assertEquals(FileSourceKind.WORKSPACE, ws.kind)
        assertTrue(ws.supportsMutation)
    }

    // ── Browse + sort (路径面包屑 + 名称/时间/大小排序) ──────────────────────────────

    @Test
    fun listSortsDirectoriesFirstThenByName() {
        seedText("work/b.txt", "b")
        seedText("work/a.txt", "a")
        Files.createDirectories(real("work/sub"))

        val entries = service.list(scopeId, "work", SortKey.NAME)
        assertEquals(listOf("sub", "a.txt", "b.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals("work/sub", entries[0].relativePath)
    }

    @Test
    fun listAtWorkspaceRootHidesHelixInternals() {
        seedText("work/a.txt", "a")
        // A file parked in the trash so `.helix/` is non-trivial but must still be hidden.
        seedText("work/doomed.txt", "x")
        service.trash(scopeId, "work/doomed.txt")

        val rootEntries = service.list(scopeId, "")
        assertEquals(listOf("input", "output", "work"), rootEntries.map { it.name })
        assertFalse(rootEntries.any { it.name == WorkspaceLayout.HELIX })
    }

    @Test
    fun listSortsBySizeDescending() {
        seedText("work/small.txt", "a")
        seedText("work/big.txt", "much longer content")

        val entries = service.list(scopeId, "work", SortKey.SIZE)
        assertEquals(listOf("big.txt", "small.txt"), entries.map { it.name })
    }

    @Test
    fun listSortsByTimeNewestFirst() {
        seedText("work/old.txt", "1")
        seedText("work/new.txt", "2")
        Files.setLastModifiedTime(real("work/old.txt"), FileTime.fromMillis(1_000L))
        Files.setLastModifiedTime(real("work/new.txt"), FileTime.fromMillis(2_000L))

        val entries = service.list(scopeId, "work", SortKey.TIME)
        assertEquals(listOf("new.txt", "old.txt"), entries.map { it.name })
    }

    @Test
    fun listOfMissingDirectoryFailsClosed() {
        assertThrows(FileNotFoundException::class.java) { service.list(scopeId, "work/nope") }
    }

    // ── Preview + info (文本/图片预览, MIME/大小/哈希) ──────────────────────────────

    @Test
    fun previewTextReturnsTextForTextFileAndNullOtherwise() {
        seedText("work/note.txt", "hello world")
        seed("work/img.png", pngBytes())

        assertEquals("hello world", service.previewText(scopeId, "work/note.txt"))
        assertNull(service.previewText(scopeId, "work/img.png"))
        assertNull(service.previewText(scopeId, "work/absent.txt"))
    }

    @Test
    fun previewImageBytesReturnsImageBytesAndEmptyForNonImage() {
        val png = pngBytes()
        seed("work/img.png", png)
        seedText("work/note.txt", "hi")

        assertEquals(png.toList(), service.previewImageBytes(scopeId, "work/img.png").toList())
        assertTrue(service.previewImageBytes(scopeId, "work/note.txt").isEmpty())
    }

    @Test
    fun fileInfoReportsSizeMimeTextFlagAndSha256() {
        val content = "hello file".toByteArray()
        seed("work/data.txt", content)

        val meta = service.fileInfo(scopeId, "work/data.txt")
        assertEquals(content.size.toLong(), meta.sizeBytes)
        assertTrue(meta.isText)
        assertFalse(meta.hashOmittedBecauseTooLarge)
        assertEquals(sha256Hex(content), meta.sha256)
    }

    @Test
    fun fileInfoReportsBinaryMimeAndNonText() {
        seed("work/img.png", pngBytes())

        val meta = service.fileInfo(scopeId, "work/img.png")
        assertEquals("image/png", meta.mimeType)
        assertFalse(meta.isText)
    }

    // ── Mutations (rename/copy, explicit conflict, NO default overwrite) ─────────────

    @Test
    fun renameConflictsWithoutOverwriteAndReplacesWithIt() {
        seedText("work/a.txt", "a")
        seedText("work/b.txt", "b")

        assertEquals(
            FileManagerService.FileOpResult.Conflict,
            service.rename(scopeId, "work/a.txt", "work/b.txt", overwrite = false),
        )
        // The failed rename leaves both files intact.
        assertTrue(real("work/a.txt").toFile().exists())
        assertEquals("b", real("work/b.txt").toFile().readText())

        val ok = service.rename(scopeId, "work/a.txt", "work/b.txt", overwrite = true)
        assertTrue(ok is FileManagerService.FileOpResult.Ok)
        assertTrue((ok as FileManagerService.FileOpResult.Ok).overwritten)
        assertFalse(real("work/a.txt").toFile().exists())
        assertEquals("a", real("work/b.txt").toFile().readText())
    }

    @Test
    fun copyKeepsSourceAndHonorsConflict() {
        seedText("work/src.txt", "data")
        seedText("work/dst.txt", "old")

        assertEquals(
            FileManagerService.FileOpResult.Conflict,
            service.copy(scopeId, "work/src.txt", "work/dst.txt", overwrite = false),
        )
        val ok = service.copy(scopeId, "work/src.txt", "work/dst.txt", overwrite = true)
        assertTrue(ok is FileManagerService.FileOpResult.Ok)
        // Copy (not move): the source is preserved.
        assertTrue(real("work/src.txt").toFile().exists())
        assertEquals("data", real("work/dst.txt").toFile().readText())
    }

    @Test
    fun renameOfMissingSourceReportsNotFound() {
        val result = service.rename(scopeId, "work/missing.txt", "work/x.txt", overwrite = false)
        assertTrue(result is FileManagerService.FileOpResult.NotFound)
    }

    @Test
    fun mutationOutsideAUserRegionFailsClosed() {
        // The region guard is what keeps all-files scopes (non-workspace layout) read-only: a
        // destination that is not inside input/work/output is refused before touching any file.
        seedText("work/a.txt", "a")
        val result = service.copy(scopeId, "work/a.txt", ".helix/steal.txt", overwrite = false)
        assertTrue(result is FileManagerService.FileOpResult.Error)
        assertFalse(real(".helix/steal.txt").toFile().exists())
    }

    @Test
    fun nextAvailableNameAppendsNumericSuffix() {
        seedText("work/a.txt", "a")
        seedText("work/a (1).txt", "x")

        assertEquals("work/a (2).txt", service.nextAvailableName(scopeId, "work/a.txt"))
    }

    @Test
    fun makeDirectoryCreatesAndRefusesAnExistingTarget() {
        val ok = service.makeDirectory(scopeId, "work", "newdir")
        assertTrue(ok is FileManagerService.FileOpResult.Ok)
        assertTrue(real("work/newdir").toFile().isDirectory)

        assertEquals(FileManagerService.FileOpResult.Conflict, service.makeDirectory(scopeId, "work", "newdir"))
    }

    // ── Trash (删除到回收站 / 恢复 / 永久删除 / 清空) ───────────────────────────────

    @Test
    fun trashParksTheFileAndRestoreReturnsItToTheOriginalPath() {
        seedText("work/doomed.txt", "bye")
        assertTrue(service.trash(scopeId, "work/doomed.txt") is FileManagerService.FileOpResult.Ok)
        assertFalse(real("work/doomed.txt").toFile().exists())

        val trash = service.listTrash(scopeId)
        assertEquals(1, trash.size)
        assertEquals("work/doomed.txt", trash[0].originalRelativePath)

        val restored = service.restore(scopeId, trash[0].entryName)
        assertTrue(restored is FileManagerService.FileOpResult.Ok)
        assertEquals("bye", real("work/doomed.txt").toFile().readText())
        assertTrue(service.listTrash(scopeId).isEmpty())
    }

    @Test
    fun restoreConflictsWhenTheOriginalPathIsReoccupied() {
        seedText("work/keep.txt", "v1")
        service.trash(scopeId, "work/keep.txt")
        seedText("work/keep.txt", "v2")

        val trash = service.listTrash(scopeId)
        assertEquals(FileManagerService.FileOpResult.Conflict, service.restore(scopeId, trash[0].entryName))
        // The occupant is untouched and the entry stays recoverable.
        assertEquals("v2", real("work/keep.txt").toFile().readText())
        assertEquals(1, service.listTrash(scopeId).size)
    }

    @Test
    fun purgeRemovesOneEntryAndEmptyTrashRemovesTheRest() {
        seedText("work/p1.txt", "1")
        seedText("work/p2.txt", "2")
        service.trash(scopeId, "work/p1.txt")
        service.trash(scopeId, "work/p2.txt")
        assertEquals(2, service.listTrash(scopeId).size)

        val first = service.listTrash(scopeId)[0]
        assertTrue(service.purge(scopeId, first.entryName) is FileManagerService.FileOpResult.Ok)
        assertEquals(1, service.listTrash(scopeId).size)

        assertEquals(1, service.emptyTrash(scopeId))
        assertTrue(service.listTrash(scopeId).isEmpty())
    }

    // ── Batch (多选) with a conflict policy + partial-failure list ───────────────────

    @Test
    fun batchCopySucceedsForEveryItemWhenNoConflict() {
        seedText("work/ba.txt", "a")
        seedText("work/bb.txt", "b")
        Files.createDirectories(real("work/dest"))

        val result =
            service.batchMoveOrCopy(
                scopeId,
                listOf("work/ba.txt", "work/bb.txt"),
                "work/dest",
                ConflictPolicy.SKIP,
                move = false,
            )
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.outcome == FileManagerService.BatchItem.Outcome.SUCCEEDED })
        assertTrue(result.failures.isEmpty())
        assertTrue(real("work/dest/ba.txt").toFile().exists())
        assertTrue(real("work/dest/bb.txt").toFile().exists())
    }

    @Test
    fun batchSkipAndAskFailClosedOnConflictNeverOverwriting() {
        for (policy in listOf(ConflictPolicy.SKIP, ConflictPolicy.ASK)) {
            Files.createDirectories(real("work/$policy"))
            seedText("work/$policy/target.txt", "old")
            seedText("work/$policy/source.txt", "new")

            val result =
                service.batchMoveOrCopy(
                    scopeId,
                    listOf("work/$policy/source.txt"),
                    "work/$policy",
                    policy,
                    move = false,
                )
            assertEquals(FileManagerService.BatchItem.Outcome.SKIPPED, result.items[0].outcome)
            assertEquals(1, result.failures.size)
            // 禁止默认覆盖: the destination keeps its original content.
            assertEquals("old", real("work/$policy/target.txt").toFile().readText())
        }
    }

    @Test
    fun batchRenameRenamesToAnAvailableSibling() {
        // A copy into a folder is named by the source's base name, so the occupant must share it.
        Files.createDirectories(real("work/dest"))
        seedText("work/dest/src-r.txt", "old")
        seedText("work/src-r.txt", "new")

        val result =
            service.batchMoveOrCopy(
                scopeId,
                listOf("work/src-r.txt"),
                "work/dest",
                ConflictPolicy.RENAME,
                move = false,
            )
        val item = result.items[0]
        assertEquals(FileManagerService.BatchItem.Outcome.RENAMED, item.outcome)
        assertEquals("work/dest/src-r (1).txt", item.detail)
        assertTrue(real(item.detail).toFile().exists())
        assertEquals("new", real(item.detail).toFile().readText())
        // The pre-existing occupant is untouched (禁止默认覆盖).
        assertEquals("old", real("work/dest/src-r.txt").toFile().readText())
    }

    @Test
    fun batchOverwriteReplacesOccupiedDestinations() {
        seedText("work/dest/d.txt", "old")
        seedText("work/d.txt", "new")

        val result =
            service.batchMoveOrCopy(
                scopeId,
                listOf("work/d.txt"),
                "work/dest",
                ConflictPolicy.OVERWRITE,
                move = false,
            )
        assertEquals(FileManagerService.BatchItem.Outcome.SUCCEEDED, result.items[0].outcome)
        assertEquals("new", real("work/dest/d.txt").toFile().readText())
    }

    @Test
    fun batchMoveRemovesTheSourceOnSuccess() {
        seedText("work/m.txt", "m")
        Files.createDirectories(real("work/dest"))

        val result =
            service.batchMoveOrCopy(
                scopeId,
                listOf("work/m.txt"),
                "work/dest",
                ConflictPolicy.SKIP,
                move = true,
            )
        assertEquals(FileManagerService.BatchItem.Outcome.SUCCEEDED, result.items[0].outcome)
        assertFalse(real("work/m.txt").toFile().exists())
        assertTrue(real("work/dest/m.txt").toFile().exists())
    }

    @Test
    fun batchReportsProgressPerItemAndStopsWhenCancelled() {
        seedText("work/c1.txt", "1")
        seedText("work/c2.txt", "2")
        seedText("work/c3.txt", "3")
        Files.createDirectories(real("work/dest"))

        val progressCalls = mutableListOf<Int>()
        val result =
            service.batchMoveOrCopy(
                scopeId,
                listOf("work/c1.txt", "work/c2.txt", "work/c3.txt"),
                "work/dest",
                ConflictPolicy.SKIP,
                move = false,
                progress = { done, _ -> progressCalls.add(done) },
                // Signal cancel after the first item has completed.
                shouldCancel = { progressCalls.size >= 1 },
            )
        // The first item is done; the remaining two are reported as skipped (cancelled), not lost.
        assertEquals(FileManagerService.BatchItem.Outcome.SUCCEEDED, result.items[0].outcome)
        assertEquals(FileManagerService.BatchItem.Outcome.SKIPPED, result.items[1].outcome)
        assertEquals(FileManagerService.BatchItem.Outcome.SKIPPED, result.items[2].outcome)
        assertEquals(listOf(1, 2, 3), progressCalls)
        assertEquals(2, result.failures.size)
        // Only the first item was actually copied.
        assertTrue(real("work/dest/c1.txt").toFile().exists())
        assertFalse(real("work/dest/c2.txt").toFile().exists())
    }

    @Test
    fun batchTrashParksEachFileAndHonorsCancel() {
        seedText("work/t1.txt", "1")
        seedText("work/t2.txt", "2")
        seedText("work/t3.txt", "3")

        val progressCalls = mutableListOf<Int>()
        val result =
            service.batchTrash(
                scopeId,
                listOf("work/t1.txt", "work/t2.txt", "work/t3.txt"),
                progress = { done, _ -> progressCalls.add(done) },
                shouldCancel = { progressCalls.size >= 1 },
            )
        assertEquals(FileManagerService.BatchItem.Outcome.SUCCEEDED, result.items[0].outcome)
        assertEquals(FileManagerService.BatchItem.Outcome.SKIPPED, result.items[1].outcome)
        assertEquals(FileManagerService.BatchItem.Outcome.SKIPPED, result.items[2].outcome)
        assertEquals(listOf(1, 2, 3), progressCalls)
        // Only the first file was actually trashed (its original path is freed); the rest remain.
        assertFalse(real("work/t1.txt").toFile().exists())
        assertTrue(real("work/t2.txt").toFile().exists())
        assertEquals(1, service.listTrash(scopeId).size)
    }

    // ── Share handoff (real file for the FileProvider URI) ────────────────────────────

    @Test
    fun realFileForReturnsTheUnderlyingRegularFile() {
        seedText("work/share.txt", "share me")

        val file = service.realFileFor(scopeId, "work/share.txt")
        assertTrue(file.exists())
        assertEquals("share me", file.readText())
    }

    @Test
    fun realFileForOfMissingPathFailsClosed() {
        assertThrows(FileNotFoundException::class.java) { service.realFileFor(scopeId, "work/absent.txt") }
    }
}
