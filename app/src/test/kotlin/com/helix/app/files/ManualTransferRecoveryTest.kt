package com.helix.app.files

import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ManualTransferRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: Path
    private lateinit var journalRoot: Path
    private lateinit var nio: NioManualFileBackend
    private var writable = true

    private class ProcessDeath : Error()

    @Before fun setup() {
        root = temp.newFolder("files").toPath()
        journalRoot = temp.newFolder("private").toPath()
        nio = NioManualFileBackend("manual", ScopeRootResolver { root }, false) { check(writable) }
        put("source", "new")
        put("target", "old")
    }

    private fun put(
        name: String,
        value: String,
    ) {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, value.toByteArray())
    }

    private fun text(name: String) = Files.readAllBytes(root.resolve(name)).toString(Charsets.UTF_8)

    private fun operations(fs: ManualFileBackend = nio) =
        ManualFileOperations({ fs }, { writable }, ManualTransferJournal(journalRoot))

    private fun crashAfterRename(backup: Boolean) {
        val fs =
            object : ManualFileBackend by nio {
                override fun rename(
                    path: String,
                    destination: String,
                ) {
                    nio.rename(path, destination)
                    if (destination.startsWith(".helix-backup-") == backup) throw ProcessDeath()
                }
            }
        assertThrows(ProcessDeath::class.java) {
            operations(fs).transfer("manual", "source", "manual", "target", true, true)
        }
    }

    @Test fun restartRestoresBackupBeforePublicationAndAllowsExplicitRetry() {
        crashAfterRename(true)
        val restarted = operations()
        val record = restarted.pendingTransfers().single()
        assertFalse(restarted.recoverTransfer(record.id))
        assertEquals("old", text("target"))
        assertEquals("new", text("source"))
        assertTrue(restarted.pendingTransfers().isEmpty())
        restarted.transfer("manual", "source", "manual", "target", true, true)
        assertEquals("new", text("target"))
        assertFalse(Files.exists(root.resolve("source")))
    }

    @Test fun renameCompletedBeforeJournalUpdateIsReconciledWithoutDeletingSource() {
        crashAfterRename(false)
        val restarted = operations()
        assertTrue(restarted.recoverTransfer(restarted.pendingTransfers().single().id))
        assertEquals("new", text("target"))
        assertEquals("new", text("source"))
        assertEquals(setOf("source", "target"), nio.children("").toSet())
    }

    @Test fun changedDestinationRetainsBackupAndRecord() {
        crashAfterRename(false)
        put("target", "user edit")
        val restarted = operations()
        val id = restarted.pendingTransfers().single().id
        assertThrows(IllegalStateException::class.java) { restarted.recoverTransfer(id) }
        assertEquals("user edit", text("target"))
        assertEquals(1, restarted.pendingTransfers().size)
        assertTrue(nio.children("").any { it.startsWith(".helix-backup-") })
    }

    @Test fun revokedPermissionCanBeRestoredAndRecoveryDoesNotReplay() {
        crashAfterRename(false)
        val restarted = operations()
        val id = restarted.pendingTransfers().single().id
        writable = false
        assertThrows(IllegalStateException::class.java) { restarted.recoverTransfer(id) }
        writable = true
        assertTrue(restarted.recoverTransfer(id))
        assertThrows(NoSuchElementException::class.java) { restarted.recoverTransfer(id) }
        assertEquals("new", text("source"))
    }

    @Test fun partialCopyIsRemovedAfterRestartWithoutChangingExistingTarget() {
        val fs =
            object : ManualFileBackend by nio {
                override fun create(
                    path: String,
                    directory: Boolean,
                ) {
                    nio.create(path, directory)
                    throw ProcessDeath()
                }
            }
        assertThrows(ProcessDeath::class.java) {
            operations(fs).transfer("manual", "source", "manual", "target", false, true)
        }
        val restarted = operations()
        assertFalse(restarted.recoverTransfer(restarted.pendingTransfers().single().id))
        assertEquals("old", text("target"))
        assertEquals("new", text("source"))
    }

    @Test fun partialDirectoryMovePreservesRemainingSourceAndCompleteDestination() {
        put("folder/a", "a")
        put("folder/b", "b")
        val fs =
            object : ManualFileBackend by nio {
                override fun delete(path: String) {
                    nio.delete(path)
                    if (path.startsWith("folder/")) throw ProcessDeath()
                }
            }
        assertThrows(ProcessDeath::class.java) {
            operations(fs).transfer("manual", "folder", "manual", "moved", true, false)
        }
        val before = nio.children("folder")
        val restarted = operations()
        assertTrue(restarted.recoverTransfer(restarted.pendingTransfers().single().id))
        assertEquals(before, nio.children("folder"))
        assertEquals("a", text("moved/a"))
        assertEquals("b", text("moved/b"))
    }

    @Test fun changedBackupIsNotDeleted() {
        crashAfterRename(false)
        val backup = nio.children("").single { it.startsWith(".helix-backup-") }
        put(backup, "keep")
        val restarted = operations()
        assertThrows(IllegalStateException::class.java) {
            restarted.recoverTransfer(restarted.pendingTransfers().single().id)
        }
        assertEquals("keep", text(backup))
    }

    @Test fun changedNewDestinationWithoutBackupKeepsRecoveryRecord() {
        Files.delete(root.resolve("target"))
        crashAfterRename(false)
        put("target", "edited")
        val restarted = operations()
        val id = restarted.pendingTransfers().single().id
        assertThrows(IllegalStateException::class.java) { restarted.recoverTransfer(id) }
        assertEquals("edited", text("target"))
        assertEquals(1, restarted.pendingTransfers().size)
    }

    @Test fun unknownJournalVersionDoesNotBecomeEmptySuccess() {
        crashAfterRename(false)
        Files.list(journalRoot).use { files ->
            val path = files.filter { it.toString().endsWith(".record") }.findFirst().get()
            Files.write(path, String(Files.readAllBytes(path)).replace("version=1", "version=2").toByteArray())
        }
        assertThrows(IllegalStateException::class.java) { operations().pendingTransfers() }
        assertEquals("new", text("target"))
    }
}
