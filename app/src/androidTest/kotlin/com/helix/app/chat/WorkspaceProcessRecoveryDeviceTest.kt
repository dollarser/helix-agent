package com.helix.app.chat

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Two instrumentations separated by actual app process death; never clears application data. */
class WorkspaceProcessRecoveryDeviceTest {
    @Test fun explicitCleanupResumesAfterProcessDeathWithoutAutomaticDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val content = File(context.cacheDir, "hxa210-process-recovery/content")
        val savedId = File(content.parentFile, "workspace-id")
        val storage = HelixStorage.open(context, DATABASE, content)
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase?.startsWith("setup") == true) {
            check(!savedId.exists()) { "Fixture state already exists" }
            val session = storage.sessions.create("owner", "Process recovery", null, null, 1)
            val id = requireNotNull(storage.workspaces.binding(session.id)).workspaceId
            val root = storage.workspaces.managedDirectory(id)
            Files.write(root.resolve("retained.txt"), "retained".toByteArray())
            storage.deleteSessionPermanently(session.id)
            val quarantine = root.parent.resolveSibling("managed-cleanup").resolve(id)
            // Force cleanup to stop after committing its fence, with no destructive work.
            Files.createDirectories(quarantine)
            assertThrows(IllegalStateException::class.java) { storage.workspaceCleanup.cleanup(id) { false } }
            assertEquals("CLEANUP_FENCED", storage.workspaces.find(id)!!.availability)
            Files.delete(quarantine)
            prepareCut(context, phase, id, root, quarantine)
            savedId.writeText(id)
            File(content.parentFile, "cut").writeText(phase)
            File(context.noBackupFilesDir, "recovery-device-pid").writeText(Process.myPid().toString())
            // Deliberately do not close Room: verify durable WAL and FS facts after abrupt death.
            Process.killProcess(Process.myPid())
            error("Expected process death")
        }
        try {
            assertTrue(savedId.exists())
            val oldPid = File(context.noBackupFilesDir, "recovery-device-pid").readText().toInt()
            assertNotEquals(oldPid, Process.myPid())
            val id = savedId.readText()
            val quarantine = content.parentFile!!.toPath().resolve("workspaces-$DATABASE/managed-cleanup/$id")
            val cut = File(content.parentFile, "cut").readText()
            val expected = if (cut in setOf("setup-purging", "setup-purged")) "CLEANUP_PURGING" else "CLEANUP_FENCED"
            assertEquals(expected, storage.workspaces.find(id)!!.availability)
            verifyCut(cut, quarantine, id)
            storage.workspaceCleanup.cleanup(id) { false }
            assertEquals("DELETED", storage.workspaces.find(id)!!.availability)
            assertFalse(Files.exists(quarantine))
            storage.workspaceCleanup.cleanup(id) { false }
            assertEquals("DELETED", storage.workspaces.find(id)!!.availability)
        } finally {
            storage.close()
            context.deleteDatabase(DATABASE)
            content.parentFile?.deleteRecursively()
            File(context.noBackupFilesDir, "recovery-device-pid").delete()
        }
    }

    private fun prepareCut(
        context: Context,
        phase: String,
        id: String,
        root: java.nio.file.Path,
        quarantine: java.nio.file.Path,
    ) {
        require(phase in setOf("setup", "setup-before-rename", "setup-purging", "setup-purged"))
        if (phase == "setup-before-rename") return
        Files.move(root, quarantine)
        if (phase in setOf("setup-purging", "setup-purged")) {
            // Seed the precise durable crash state in this test-only DB; production CAS/purge is host-tested.
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE).path, null, 0).use {
                it.execSQL("UPDATE workspaces SET availability = 'CLEANUP_PURGING' WHERE id = ?", arrayOf(id))
            }
            Files.write(quarantine.resolve("removed.txt"), "partial purge".toByteArray())
            Files.delete(quarantine.resolve("removed.txt"))
            if (phase == "setup-purged") {
                Files.delete(quarantine.resolve("retained.txt"))
                Files.delete(quarantine)
            }
        }
    }

    private fun verifyCut(
        cut: String,
        quarantine: java.nio.file.Path,
        id: String,
    ) {
        if (cut == "setup-purged") {
            assertFalse(Files.exists(quarantine))
        } else {
            val retained =
                if (cut ==
                    "setup-before-rename"
                ) {
                    quarantine.parent.resolveSibling("managed").resolve(id)
                } else {
                    quarantine
                }
            assertEquals("retained", String(Files.readAllBytes(retained.resolve("retained.txt"))))
        }
    }

    private companion object {
        const val DATABASE = "hxa210-process-recovery.db"
    }
}
