package com.helix.core.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DocumentWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val backend = Documents()
    private val path = FileScopePath("documents", "file.txt")

    private fun store() =
        WorkspaceArtifactStore(
            ScopeRootResolver { error("A document must never become a local cwd") },
            documentBackend = { backend },
            metadataRoot = { temporary.root.toPath().resolve("metadata") },
        )

    @Test fun readWriteAndVersionConflictUseTheDocumentInPlace() {
        val store = store()
        val output = store.writeArtifact(path, "héllo".toByteArray(), WorkspaceLayout.ROOT_FILES)
        assertEquals(-1L, output.usageBytesAfter)
        assertEquals("héllo", store.readWindow(path, 0, 100).text)
        assertThrows(PreconditionHashMismatch::class.java) {
            store.writeArtifact(path, "stale".toByteArray(), WorkspaceLayout.ROOT_FILES, "0".repeat(64))
        }
        assertEquals("héllo", String(backend.files.getValue("file.txt")))
    }

    @Test fun partialWriteNeverClaimsRollbackOrSuccess() {
        backend.files["file.txt"] = "old".toByteArray()
        backend.failWrite = true
        assertThrows(WorkspaceMutationUncertain::class.java) {
            store().writeArtifact(path, "new".toByteArray(), WorkspaceLayout.ROOT_FILES)
        }
        assertArrayEquals(byteArrayOf('n'.code.toByte()), backend.files.getValue("file.txt"))
    }

    @Test fun recoverableDeleteSurvivesReopenAndRefusesRestoreConflict() {
        backend.files["file.txt"] = "keep".toByteArray()
        val removed = store().moveToTrash(path)
        assertFalse(backend.files.containsKey("file.txt"))
        val ref = FileScopePath(path.scopeId, "${WorkspaceLayout.TRASH}/${removed.trashName}")
        backend.files["file.txt"] = "replacement".toByteArray()
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { store().restoreFromTrash(ref) }
        assertEquals("replacement", String(backend.files.getValue("file.txt")))
        backend.files.remove("file.txt")
        store().restoreFromTrash(ref)
        assertEquals("keep", String(backend.files.getValue("file.txt")))
    }

    @Test fun ambiguousDeletionRetainsBackupAndNeverReplaysOnReopen() {
        backend.files["file.txt"] = "keep".toByteArray()
        backend.failDelete = true
        assertThrows(WorkspaceMutationUncertain::class.java) { store().moveToTrash(path) }
        assertEquals(1, backend.deletes)
        store()
        assertEquals(1, backend.deletes)
        val backups =
            temporary.root
                .resolve("metadata/trash")
                .listFiles()!!
                .filter { !it.name.endsWith(".phase") }
        assertEquals(1, backups.size)
        assertEquals("keep", backups.single().readText())
        val ref = FileScopePath(path.scopeId, "${WorkspaceLayout.TRASH}/${backups.single().name}")
        assertThrows(IllegalArgumentException::class.java) { store().purgeTrashEntry(ref) }
        assertTrue(backups.single().exists())
    }

    @Test fun modifiedBackupCannotBeRestoredAfterReopen() {
        backend.files["file.txt"] = "keep".toByteArray()
        val removed = store().moveToTrash(path)
        val backup = temporary.root.resolve("metadata/trash/${removed.trashName}")
        backup.writeText("tampered")
        val ref = FileScopePath(path.scopeId, "${WorkspaceLayout.TRASH}/${removed.trashName}")
        assertThrows(IllegalArgumentException::class.java) { store().restoreFromTrash(ref) }
        assertFalse(backend.files.containsKey("file.txt"))
        assertTrue(backup.exists())
    }

    @Test fun unknownFileSizeRefusesDeletionBeforeAnyEffect() {
        backend.files["file.txt"] = "keep".toByteArray()
        backend.unknownSize = true
        assertThrows(IllegalArgumentException::class.java) { store().moveToTrash(path) }
        assertEquals(0, backend.deletes)
        assertEquals("keep", String(backend.files.getValue("file.txt")))
    }

    @Test fun zeroProgressDocumentWindowFailsWithoutLooping() {
        val input =
            object : java.io.InputStream() {
                override fun read(): Int = 0

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertThrows(IOException::class.java) { ReadWindow.read(input, 8, 1, 4) }
        assertThrows(IOException::class.java) { ReadWindow.read(input, 8, 0, 4) }
    }

    private class Documents : WorkspaceFileBackend {
        val files = mutableMapOf<String, ByteArray>()
        var unknownSize = false
        var failWrite = false
        var failDelete = false
        var deletes = 0

        override fun validateMutation(path: String) {
            require(path.isNotEmpty())
        }

        override fun stat(path: String) =
            if (path.isEmpty()) {
                WorkspaceFileInfo(true, 0)
            } else {
                files[path]?.let {
                    WorkspaceFileInfo(false, if (unknownSize) -1 else it.size.toLong())
                }
            }

        override fun children(path: String) = files.keys.toList()

        override fun read(path: String) = ByteArrayInputStream(files.getValue(path))

        override fun create(
            path: String,
            directory: Boolean,
        ) {
            if (files.containsKey(path)) throw java.nio.file.FileAlreadyExistsException(path)
            require(!directory)
            files[path] = byteArrayOf()
        }

        override fun write(path: String) =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[path] = if (failWrite) toByteArray().take(1).toByteArray() else toByteArray()
                    if (failWrite) throw IOException("partial")
                }
            }

        override fun rename(
            path: String,
            destination: String,
        ) {
            error("unsupported")
        }

        override fun delete(path: String) {
            deletes++
            files.remove(path)
            if (failDelete) throw IOException("receipt lost")
        }
    }
}
