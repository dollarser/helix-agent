package com.helix.app.files

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class ManualFileOperationsTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: Path
    private lateinit var nio: NioManualFileBackend
    private lateinit var ops: ManualFileOperations
    private var writable = true

    @Before fun setup() {
        root = temp.newFolder().toPath()
        nio = NioManualFileBackend("manual", ScopeRootResolver { root }, false) { check(writable) }
        ops = operations(nio)
    }

    private fun operations(fs: ManualFileBackend) = ManualFileOperations({ fs }, { writable })

    private fun put(
        path: String,
        text: String,
    ) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray())
    }

    private fun content(path: String) = String(Files.readAllBytes(root.resolve(path)))

    private fun transfer(
        source: String,
        target: String,
        move: Boolean = false,
        overwrite: Boolean = false,
    ) = ops.transfer("manual", source, "manual", target, move, overwrite)

    @Test fun copiesAndMovesNestedDirectoriesAndEmptyFolders() {
        put("source/sub/file.txt", "nested")
        Files.createDirectory(root.resolve("source/empty"))
        transfer("source", "copy")
        assertEquals("nested", content("copy/sub/file.txt"))
        assertTrue(Files.isDirectory(root.resolve("copy/empty")))
        ops.mkdir("manual", "", "destination")
        transfer("copy", "destination/moved", true)
        assertFalse(Files.exists(root.resolve("copy")))
        assertEquals("nested", content("destination/moved/sub/file.txt"))
    }

    @Test fun conflictDoesNotTouchEitherFileAndExplicitOverwriteWorks() {
        put("a", "source")
        put("b", "old")
        assertThrows(FileAlreadyExistsException::class.java) { transfer("a", "b") }
        assertEquals("old", content("b"))
        assertTrue(transfer("a", "b", overwrite = true))
        assertEquals("source", content("a"))
        assertEquals("source", content("b"))
        assertEquals(listOf("a", "b"), nio.children(""))
    }

    @Test fun failurePublishingRestoresOldDestinationAndSource() {
        put("a", "source")
        put("b", "old")
        ops =
            operations(
                object : ManualFileBackend by nio {
                    override fun rename(
                        path: String,
                        destination: String,
                    ) {
                        if (path.contains(".helix-transfer-")) error("Injected publication failure")
                        nio.rename(path, destination)
                    }
                },
            )
        assertThrows(IllegalStateException::class.java) { transfer("a", "b", overwrite = true) }
        assertEquals("source", content("a"))
        assertEquals("old", content("b"))
        assertEquals(listOf("a", "b"), nio.children(""))
    }

    @Test fun cancellationDuringStreamingRetainsSourceAndRemovesPartialCopy() {
        Files.write(root.resolve("large"), ByteArray(512 * 1024) { 7 })
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ops.transfer("manual", "large", "manual", "target", false, false) { ++checks >= 4 }
        }
        assertEquals(listOf("large"), nio.children(""))
        assertEquals(512L * 1024, Files.size(root.resolve("large")))
    }

    @Test fun sourceDeletionFailureReportsCopiedDestinationWithoutFalseMoveSuccess() {
        put("a", "original")
        ops.mkdir("manual", "", "folder")
        ops =
            operations(
                object : ManualFileBackend by nio {
                    override fun delete(path: String) {
                        if (path ==
                            "a"
                        ) {
                            error("Injected delete failure")
                        } else {
                            nio.delete(path)
                        }
                    }
                },
            )
        val failure = assertThrows(IllegalStateException::class.java) { transfer("a", "folder/b", true) }
        assertTrue(failure.message!!.contains("Destination exists"))
        assertEquals("original", content("a"))
        assertEquals("original", content("folder/b"))
    }

    @Test fun revokedPermissionRefusesMutation() {
        put("a", "original")
        writable = false
        assertThrows(IllegalStateException::class.java) { transfer("a", "b", true) }
        assertThrows(IllegalStateException::class.java) { ops.delete("manual", "a") }
        assertThrows(IllegalStateException::class.java) { ops.mkdir("manual", "", "new") }
        assertEquals("original", content("a"))
    }

    @Test fun changingSourceDuringCopyDoesNotPublishOrDeleteIt() {
        put("a", "original")
        var reads = 0
        ops =
            operations(
                object : ManualFileBackend by nio {
                    override fun read(path: String): java.io.InputStream {
                        if (path == "a" && ++reads == 2) put("a", "edited")
                        return nio.read(path)
                    }
                },
            )
        assertThrows(IllegalStateException::class.java) { transfer("a", "b") }
        assertEquals("edited", content("a"))
        assertEquals(listOf("a"), nio.children(""))
    }

    @Test fun rejectsRootsSelfDescendantsAncestorsAndSymlinks() {
        put("dir/a", "original")
        assertThrows(IllegalArgumentException::class.java) { transfer("dir", "dir/sub") }
        assertThrows(IllegalArgumentException::class.java) { transfer("dir/a", "dir", overwrite = true) }
        assertThrows(IllegalArgumentException::class.java) { transfer("dir", "dir") }
        assertThrows(IllegalArgumentException::class.java) { ops.delete("manual", "") }
        val outside = temp.newFile().toPath()
        Files.createSymbolicLink(root.resolve("link"), outside)
        assertThrows(Exception::class.java) { transfer("link", "copy") }
        assertTrue(Files.exists(outside))
    }

    @Test fun directoryTrashRestoreConflictAndPurgePreserveOriginals() {
        val resolver = ScopeRootResolver { root }
        val store = WorkspaceArtifactStore(resolver)
        store.ensureLayout("ws")
        val manager = FileManagerService(store, resolver, "ws")
        put("work/folder/a", "original")
        assertTrue(manager.trash("ws", "work/folder") is FileManagerService.FileOpResult.Ok)
        val entry = manager.listTrash("ws").single()
        put("work/folder/other", "keep")
        assertEquals(FileManagerService.FileOpResult.Conflict, manager.restore("ws", entry.entryName))
        Files.delete(root.resolve("work/folder/other"))
        Files.delete(root.resolve("work/folder"))
        assertTrue(manager.restore("ws", entry.entryName) is FileManagerService.FileOpResult.Ok)
        assertEquals("original", content("work/folder/a"))
        manager.trash("ws", "work/folder")
        assertTrue(
            manager.purge("ws", manager.listTrash("ws").single().entryName) is FileManagerService.FileOpResult.Ok,
        )
        assertTrue(manager.listTrash("ws").isEmpty())
    }

    @Test fun workspaceRootRegionAndInternalStateRemainProtected() {
        val resolver = ScopeRootResolver { root }
        WorkspaceArtifactStore(resolver).ensureLayout("ws")
        val backend = NioManualFileBackend("ws", resolver, true) {}
        val workspaceOps = operations(backend)
        assertThrows(IllegalArgumentException::class.java) { workspaceOps.delete("ws", "work") }
        assertThrows(IllegalArgumentException::class.java) { workspaceOps.mkdir("ws", "", "extra") }
        assertThrows(IllegalArgumentException::class.java) { workspaceOps.delete("ws", ".helix/trash") }
        assertTrue(Files.isDirectory(root.resolve("work")))
    }
}
