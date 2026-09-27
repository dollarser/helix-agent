package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual Room close/reopen fixture. Compilation alone does not constitute device acceptance. */
@RunWith(AndroidJUnit4::class)
class WorkspaceBindingDeviceTest {
    @Test fun directoryIdentitySurvivesContentChangesButRejectsReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workspace-identity-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "$name/content")
        val storage = HelixStorage.open(context, name, content)
        try {
            val session = storage.sessions.create("owner", "Owner", null, null, 1)
            val id = requireNotNull(storage.workspaces.binding(session.id)).workspaceId
            val root = storage.workspaces.managedDirectory(id)
            val before = storage.workspaces.directoryWitness(root)
            Files.write(root.resolve("child"), byteArrayOf(1))
            Files.setLastModifiedTime(
                root,
                java.nio.file.attribute.FileTime
                    .fromMillis(1234567890000),
            )
            assertEquals(before, storage.workspaces.directoryWitness(root))
            assertEquals(root, storage.workspaces.managedDirectory(id))
            val moved = root.resolveSibling("$id-moved")
            Files.move(root, moved)
            assertEquals(before, storage.workspaces.directoryWitness(moved))
            Files.createDirectory(root)
            assertNotEquals(before, storage.workspaces.directoryWitness(root))
            assertThrows(IllegalStateException::class.java) { storage.workspaces.managedDirectory(id) }
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.parentFile?.deleteRecursively()
        }
    }

    @Test fun cleanupFenceRejectsLateArtifactRegistrationAfterReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workspace-fence-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "$name/content")
        var storage = HelixStorage.open(context, name, content)
        try {
            val owner = storage.sessions.create("owner", "Owner", null, null, 1)
            val survivor = storage.sessions.create("survivor", "Survivor", null, null, 2)
            val id = requireNotNull(storage.workspaces.binding(owner.id)).workspaceId
            val root = storage.workspaces.managedDirectory(id)
            val file = root.resolve("keep.txt")
            Files.write(file, "keep".toByteArray())
            storage.deleteSessionPermanently(owner.id)
            // A foreign quarantine identity forces a stop after the durable fence, before deletion.
            val quarantine = root.parent.resolveSibling("managed-cleanup").resolve(id)
            Files.createDirectories(quarantine)
            assertThrows(IllegalStateException::class.java) { storage.workspaceCleanup.cleanup(id) { false } }
            storage.close()
            storage = HelixStorage.open(context, name, content)
            assertEquals("CLEANUP_FENCED", storage.workspaces.find(id)!!.availability)
            assertThrows(IllegalArgumentException::class.java) {
                storage.artifacts.register(
                    "late",
                    survivor.id,
                    "scope:$id:keep.txt",
                    "text/plain",
                    4,
                    com.helix.core.workspace.AtomicFileWriter
                        .sha256Hex(file),
                    file.toFile(),
                )
            }
            assertTrue(storage.artifacts.listBySession(survivor.id).isEmpty())
            assertEquals("keep", String(Files.readAllBytes(file)))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.parentFile?.deleteRecursively()
        }
    }

    @Test fun requestBindingAndFilesSurviveRoomReopenAndSessionSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workspace-binding-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "$name/content")
        var storage = HelixStorage.open(context, name, content)
        try {
            val first = storage.sessions.create("first", "First", null, null, 1)
            val second = storage.sessions.create("second", "Second", null, null, 2)
            assertNotEquals(first.directoryRef, second.directoryRef)
            val binding = requireNotNull(storage.workspaces.binding(first.id))
            val root = storage.workspaces.managedDirectory(binding.workspaceId)
            Files.write(root.resolve("keep.txt"), "kept".toByteArray())
            storage.turns.start("turn", first.id, 3)
            storage.modelCalls.append("request", "turn", "fixture", "RUNNING")
            storage.workspaces.recordRequest("request", binding)
            storage.sessions.updateDetails(first.id, first.title, second.directoryRef)
            storage.artifacts.register(
                "shared-artifact",
                second.id,
                "scope:${binding.workspaceId}:keep.txt",
                "text/plain",
                4,
                com.helix.core.workspace.AtomicFileWriter
                    .sha256Hex(root.resolve("keep.txt")),
                root.resolve("keep.txt").toFile(),
            )
            storage.close()
            storage = HelixStorage.open(context, name, content)
            assertEquals(second.directoryRef, storage.sessions.resolve(first.id).directoryRef)
            val captured = requireNotNull(storage.workspaces.requestBinding("request"))
            assertEquals(binding.workspaceId, captured.workspaceId)
            assertEquals(binding.revision, captured.bindingRevision)
            assertEquals("kept", String(Files.readAllBytes(root.resolve("keep.txt"))))
            val beforeDeletion = storage.workspaces.retentionReferences(binding.workspaceId)
            assertEquals(1, beforeDeletion.ownerSessions)
            assertEquals(0, beforeDeletion.sessionBindings)
            assertEquals(1, beforeDeletion.modelRequests)
            assertEquals(1, beforeDeletion.artifacts)
            storage.deleteSessionPermanently(first.id)
            val afterDeletion = storage.workspaces.retentionReferences(binding.workspaceId)
            assertEquals(0, afterDeletion.ownerSessions)
            assertEquals(0, afterDeletion.modelRequests)
            assertEquals(1, afterDeletion.artifacts)
            assertTrue(afterDeletion.retained)
            assertTrue(Files.exists(root.resolve("keep.txt")))
            val secondId = FileScopePath.fromModelReference(requireNotNull(second.directoryRef)).scopeId
            assertEquals(1, storage.workspaces.references(secondId))
            assertEquals(0, storage.workspaces.retentionReferences(secondId).artifacts)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            // Only this fixture's uniquely named cache tree, never app or user workspace data.
            content.parentFile?.deleteRecursively()
        }
    }
}
