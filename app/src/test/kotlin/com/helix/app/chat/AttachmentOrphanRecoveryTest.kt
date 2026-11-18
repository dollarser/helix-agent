package com.helix.app.chat

import com.helix.app.infrastructure.chat.AttachmentOrphanRecovery
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AttachmentOrphanRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unregisteredBytesRetireRecoverablyAndRegisteredSnapshotsRemain() {
        val root = temporary.newFolder().toPath()
        val orphan = "input/attachments/att_${"a".repeat(32)}/原件.txt"
        val bound = "input/attachments/att_${"b".repeat(32)}/retained.txt"
        val derived = "input/attachment-derived/${"c".repeat(64)}/image-test/normalized.png"
        val unknown = "input/attachments/unknown/user.txt"
        listOf(orphan, bound, derived, unknown).forEach {
            Files.createDirectories(root.resolve(it).parent)
            Files.write(root.resolve(it), it.toByteArray())
        }
        val recover = AttachmentOrphanRecovery(root, referenceCount = { if (it == "scope:app:$bound") 2 else 0 })
        assertEquals(2, recover.recover())
        assertEquals(0, recover.recover())
        assertTrue(Files.exists(root.resolve(bound)))
        assertTrue(Files.exists(root.resolve(unknown)))
        assertFalse(Files.exists(root.resolve(orphan)))
        val store = WorkspaceArtifactStore(rootResolver = ScopeRootResolver { root })
        Files.newDirectoryStream(root.resolve(".helix/trash")).use { entries ->
            entries.forEach { store.restoreFromTrash(FileScopePath("app", ".helix/trash/${it.fileName}")) }
        }
        assertEquals(orphan, String(Files.readAllBytes(root.resolve(orphan))))
        assertEquals(derived, String(Files.readAllBytes(root.resolve(derived))))
    }

    @Test fun linksNeverReachOutsideTheOwnedDirectory() {
        val root = temporary.newFolder().toPath()
        val external = temporary.newFolder().toPath()
        Files.write(external.resolve("keep.txt"), "keep".toByteArray())
        Files.createDirectories(root.resolve("input/attachments"))
        Files.createSymbolicLink(root.resolve("input/attachments/att_${"a".repeat(32)}"), external)
        assertEquals(0, AttachmentOrphanRecovery(root, referenceCount = { 0 }).recover())
        assertEquals("keep", String(Files.readAllBytes(external.resolve("keep.txt"))))
    }

    @Test fun unexpectedSiblingIsRetainedAndReportedInsteadOfClaimingCleanup() {
        val root = temporary.newFolder().toPath()
        val directory = root.resolve("input/attachments/att_${"a".repeat(32)}")
        Files.createDirectories(directory)
        Files.write(directory.resolve("one.txt"), byteArrayOf(1))
        Files.write(directory.resolve("two.txt"), byteArrayOf(2))
        val failures = mutableListOf<java.io.IOException>()
        assertEquals(0, AttachmentOrphanRecovery(root, { 0 }, { failures.add(it) }).recover())
        assertEquals(2, failures.size)
        assertTrue(Files.exists(directory.resolve("one.txt")))
        assertTrue(Files.exists(directory.resolve("two.txt")))
    }

    @Test fun replacedScopeRootIsNeverFollowed() {
        val external = temporary.newFolder().toPath()
        val file = external.resolve("input/attachments/att_${"a".repeat(32)}/keep.txt")
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(1))
        val link = temporary.root.toPath().resolve("linked-root")
        Files.createSymbolicLink(link, external)
        assertEquals(0, AttachmentOrphanRecovery(link, referenceCount = { 0 }).recover())
        assertTrue(Files.exists(file))
    }

    @Test fun unknownFilenameCannotBreakStartupOrRetargetCleanup() {
        val root = temporary.newFolder().toPath()
        val file = root.resolve("input/attachments/att_${"a".repeat(32)}/line\nbreak.txt")
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(1))
        assertEquals(0, AttachmentOrphanRecovery(root, referenceCount = { error("Unknown path queried") }).recover())
        assertTrue(Files.exists(file))
    }
}
