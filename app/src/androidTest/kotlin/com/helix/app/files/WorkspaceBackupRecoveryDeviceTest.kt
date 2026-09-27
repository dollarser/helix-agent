package com.helix.app.files

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.FileAlreadyExistsException

/** Actual process death inside the production backup/delete/restore protocol over a local backend. */
class WorkspaceBackupRecoveryDeviceTest {
    @Test fun backupRetainsEvidenceAcrossDeletionAndRestoreProcessDeath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "hxa210-backup-recovery")
        val root = File(fixture, "files").apply { check(mkdirs() || isDirectory) }
        val metadata = File(fixture, "metadata")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        val roots = ScopeRootResolver { root.toPath() }
        val original = NioManualFileBackend("fixture", roots, false) {}
        val backend =
            object : ManualFileBackend by original {
                override fun delete(path: String) {
                    if (phase == "setup-prepared") die(context, fixture, phase)
                    original.delete(path)
                    if (phase == "setup-deleted") die(context, fixture, phase)
                }

                override fun create(
                    path: String,
                    directory: Boolean,
                ) {
                    original.create(path, directory)
                    if (phase == "setup-restoring") die(context, fixture, phase)
                }
            }
        val store = WorkspaceArtifactStore(roots, documentBackend = { backend }, metadataRoot = { metadata.toPath() })
        val path = FileScopePath("fixture", "retained.txt")
        if (phase?.startsWith("setup") == true) {
            require(phase in setOf("setup-prepared", "setup-deleted", "setup-restoring"))
            original.create("retained.txt", false)
            original.write("retained.txt").use { it.write("retained backup".toByteArray()) }
            val entry = store.moveToTrash(path)
            store.restoreFromTrash(FileScopePath("fixture", ".helix/trash/${entry.trashName}"))
            error("Expected process death at the selected backup boundary")
        }
        try {
            verify(context, fixture, metadata, root, store)
        } finally {
            fixture.deleteRecursively()
            File(context.noBackupFilesDir, "recovery-device-pid").delete()
        }
    }

    private fun verify(
        context: Context,
        fixture: File,
        metadata: File,
        root: File,
        store: WorkspaceArtifactStore,
    ) {
        assertNotEquals(File(context.noBackupFilesDir, "recovery-device-pid").readText().toInt(), Process.myPid())
        val cut = File(fixture, "cut").readText()
        val backup = requireNotNull(File(metadata, "trash").listFiles()).single()
        assertEquals("retained backup", backup.readText())
        val receipt = File(metadata, "trash-receipts/${backup.name}")
        assertEquals(if (cut == "setup-restoring") "RESTORING" else "PREPARED", receipt.readLines().first())
        val ref = FileScopePath("fixture", ".helix/trash/${backup.name}")
        assertThrows(IllegalArgumentException::class.java) { store.purgeTrashEntry(ref) }
        val target = File(root, "retained.txt")
        if (cut != "setup-deleted") {
            assertEquals(if (cut == "setup-prepared") "retained backup" else "", target.readText())
            assertThrows(FileAlreadyExistsException::class.java) { store.restoreFromTrash(ref) }
            assertTrue(backup.exists())
            // Explicit fixture user resolves the observed conflict; startup never deletes/replays it.
            assertTrue(target.delete())
        } else {
            assertFalse(target.exists())
        }
        store.restoreFromTrash(ref)
        assertEquals("retained backup", target.readText())
        assertFalse(backup.exists())
    }

    private fun die(
        context: Context,
        fixture: File,
        phase: String,
    ): Nothing {
        File(fixture, "cut").writeText(phase)
        File(context.noBackupFilesDir, "recovery-device-pid").writeText(Process.myPid().toString())
        Process.killProcess(Process.myPid())
        error("Expected process death")
    }
}
