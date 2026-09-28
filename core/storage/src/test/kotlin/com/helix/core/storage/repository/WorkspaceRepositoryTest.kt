package com.helix.core.storage.repository

import com.helix.core.storage.dao.WorkspaceDao
import com.helix.core.storage.entity.ModelCallWorkspaceEntity
import com.helix.core.storage.entity.SessionWorkspaceEntity
import com.helix.core.storage.entity.WorkspaceEntity
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class WorkspaceRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = FakeWorkspaceDao()

    private fun repository() = WorkspaceRepository(dao, temporary.root.toPath().resolve("managed"))

    @Test fun independentDefaultsSurviveRepositoryReopenWithoutLayoutOrDataLoss() {
        val first = repository().defaultDirectory("a", 1)
        val second = repository().defaultDirectory("b", 2)
        assertNotEquals(first, second)
        val path = repository().managedDirectory(FileScopePath.fromModelReference(first).scopeId)
        assertFalse(Files.exists(path.resolve("input")))
        Files.write(path.resolve("notes.txt"), "kept".toByteArray())
        assertEquals(first, repository().defaultDirectory("a", 3))
        assertEquals("kept", String(Files.readAllBytes(path.resolve("notes.txt"))))
    }

    @Test fun sharedResourceBindingsAdvanceOnlyChangedSessionsAndFreezeRequestRevision() {
        val repo = repository()
        val external = repo.register("PATH", "scope:external:project", "inode-1", 1)
        assertEquals(external, repo.register("PATH", "scope:external:project", "inode-1", 2))
        val root = FileScopePath(external.id, "").toModelReference()
        repo.bind("a", root)
        repo.bind("b", root)
        repo.recordRequest("call-1", requireNotNull(repo.binding("a")))
        repo.bind("a", FileScopePath(external.id, "sub").toModelReference())
        repo.bind("a", FileScopePath(external.id, "sub").toModelReference())
        assertEquals(2, repo.references(external.id))
        assertEquals(2L, repo.binding("a")!!.revision)
        assertEquals(1L, repo.binding("b")!!.revision)
        assertEquals(1L, repo.requestBinding("call-1")!!.bindingRevision)
        assertEquals("", repo.requestBinding("call-1")!!.relativePath)
        assertThrows(IllegalStateException::class.java) { repo.recordRequest("call-1", repo.binding("a")!!) }
    }

    @Test fun freshRecoveryDirectoryDoesNotInvalidateSharedFilesOrFrozenRequests() {
        val repo = repository()
        val original = repo.defaultDirectory("owner", 1)
        val id = FileScopePath.fromModelReference(original).scopeId
        val file = repo.managedDirectory(id).resolve("keep.txt")
        Files.write(file, "retained".toByteArray())
        repo.bind("owner", original)
        repo.bind("fork", original)
        repo.recordRequest("old-request", requireNotNull(repo.binding("owner")))
        val fresh = repo.freshDirectory("owner", 2)
        repo.bind("owner", fresh)
        assertNotEquals(original, fresh)
        assertEquals("retained", String(Files.readAllBytes(file)))
        assertEquals("READY", repo.find(id)?.availability)
        assertEquals(id, repo.binding("fork")?.workspaceId)
        assertEquals(id, repo.requestBinding("old-request")?.workspaceId)
        assertEquals(
            0,
            repo
                .managedDirectory(FileScopePath.fromModelReference(fresh).scopeId)
                .toFile()
                .listFiles()!!
                .size,
        )
    }

    @Test fun missingDefaultDirectoryIsReplacedOnTheFirstAttempt() {
        val repo = repository()
        val original = repo.defaultDirectory("owner", 1)
        val root = repo.managedDirectory(FileScopePath.fromModelReference(original).scopeId)
        Files.delete(root)
        val recovered = repo.defaultDirectory("owner", 2)
        assertNotEquals(original, recovered)
        assertEquals(recovered, repo.defaultDirectory("owner", 3))
    }

    @Test fun recreatedManagedDirectoryCannotInheritTheOldIdentity() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("a", 1)).scopeId
        val root = repo.managedDirectory(id)
        Files.move(root, root.resolveSibling("retained-original"))
        Files.createDirectory(root)
        Files.write(root.resolve("foreign.txt"), "keep".toByteArray())
        assertThrows(IllegalStateException::class.java) { repo.managedDirectory(id) }
        assertEquals("UNAVAILABLE", repo.find(id)!!.availability)
        assertEquals("keep", String(Files.readAllBytes(root.resolve("foreign.txt"))))
    }

    @Test fun interruptedCreationNeverAdoptsOrRemovesUnwitnessedFiles() {
        val id = "ws-00000000-0000-0000-0000-000000000001"
        dao.insert(WorkspaceEntity(id, "PATH", id, "managed:$id", "MANAGED", "CREATING", null, null, "a", 1))
        val root =
            temporary.root
                .toPath()
                .resolve("managed")
                .resolve(id)
        Files.createDirectories(root)
        Files.write(root.resolve("keep.txt"), byteArrayOf(1))
        val recovered = repository().defaultDirectory("a", 2)
        assertNotEquals(id, FileScopePath.fromModelReference(recovered).scopeId)
        assertEquals(recovered, repository().defaultDirectory("a", 3))
        assertEquals("UNAVAILABLE", repository().find(id)!!.availability)
        assertEquals(1, Files.readAllBytes(root.resolve("keep.txt")).size)
    }

    @Test fun verifiedEquivalentPathLocatorsShareOneResource() {
        val repo = repository()
        val first = repo.register("PATH", "scope:first:project", "inode", 1, "canonical-root")
        val alias = repo.register("PATH", "scope:second:project", "inode", 2, "canonical-root")
        assertEquals(first.id, alias.id)
    }

    @Test fun newResourceAtSameLocatorDoesNotInheritProjectOrBinding() {
        val repo = repository()
        val first = repo.register("PATH", "scope:external:project", "inode-1", 1)
        val second = repo.register("PATH", "scope:external:project", "inode-2", 2)
        assertNotEquals(first.id, second.id)
        assertEquals(null, second.projectId)
        assertEquals(0, repo.references(second.id))
    }

    @Test fun explicitReregistrationAfterLossCreatesFreshIdentity() {
        val repo = repository()
        val first = repo.register("SAF", "scope:tree:folder", "document-id", 1)
        repo.markUnavailable(first.id)
        val rebound = repo.register("SAF", "scope:tree:folder", "document-id", 2)
        assertNotEquals(first.id, rebound.id)
        assertEquals(null, rebound.projectId)
        assertEquals(0, repo.references(rebound.id))
        assertEquals("UNAVAILABLE", repo.find(first.id)!!.availability)
    }

    @Test fun historicalRequestRetainsResourceAfterSessionSwitch() {
        val repo = repository()
        val old = repo.register("PATH", "scope:external:old", "old-inode", 1)
        val next = repo.register("PATH", "scope:external:new", "new-inode", 2)
        repo.bind("session", FileScopePath(old.id, "").toModelReference())
        repo.recordRequest("request", requireNotNull(repo.binding("session")))
        repo.bind("session", FileScopePath(next.id, "").toModelReference())
        val retained = repo.retentionReferences(old.id)
        assertEquals(0, retained.sessionBindings)
        assertEquals(1, retained.modelRequests)
        org.junit.Assert.assertTrue(retained.retained)
    }

    private fun cleanup() = WorkspaceCleanupRepository(dao, temporary.root.toPath().resolve("managed")) { it() }

    @Test fun cleanupRejectsLiveReferencesAndBackupsBeforeFencing() {
        val repo = repository()
        val ref = repo.defaultDirectory("owner", 1)
        val id = FileScopePath.fromModelReference(ref).scopeId
        repo.bind("shared", ref)
        assertThrows(IllegalArgumentException::class.java) { cleanup().cleanup(id) { false } }
        assertEquals("READY", repo.find(id)!!.availability)
        val unused = FileScopePath.fromModelReference(repo.defaultDirectory("unused", 2)).scopeId
        assertThrows(IllegalArgumentException::class.java) { cleanup().cleanup(unused) { true } }
        assertEquals("READY", repo.find(unused)!!.availability)
    }

    @Test fun fencedResourceRejectsNewBindingsAndRequestReferences() {
        val repo = repository()
        val ref = repo.defaultDirectory("owner", 1)
        val id = FileScopePath.fromModelReference(ref).scopeId
        assertEquals(1, dao.compareAvailability(id, "READY", "CLEANUP_FENCED"))
        assertThrows(IllegalArgumentException::class.java) { repo.bind("late", ref) }
        assertThrows(IllegalArgumentException::class.java) {
            repo.recordRequest("late-request", SessionWorkspaceEntity("late", id, "", 1))
        }
        assertEquals(null, repo.binding("late"))
        assertEquals(null, repo.requestBinding("late-request"))
    }

    @Test fun cleanupUnlinksChildSymlinkWithoutFollowingItsTargetAndIsIdempotent() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        val external = temporary.newFile("external.txt").toPath()
        Files.write(external, "keep".toByteArray())
        Files.createSymbolicLink(root.resolve("link"), external)
        cleanup().cleanup(id) { false }
        assertEquals("DELETED", repo.find(id)!!.availability)
        assertFalse(Files.exists(root))
        assertEquals("keep", String(Files.readAllBytes(external)))
        cleanup().cleanup(id) { false }
    }

    @Test fun renameInterruptionRetainsFilesUntilExplicitResume() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        Files.write(root.resolve("file.txt"), "kept".toByteArray())
        dao.compareAvailability(id, "READY", "CLEANUP_FENCED")
        val quarantine = root.parent.resolveSibling("managed-cleanup").resolve(id)
        Files.createDirectories(quarantine.parent)
        Files.move(root, quarantine)
        cleanup() // Reopen alone never deletes or replays cleanup.
        assertEquals("kept", String(Files.readAllBytes(quarantine.resolve("file.txt"))))
        cleanup().cleanup(id) { false }
        assertFalse(Files.exists(quarantine))
        assertEquals("DELETED", repo.find(id)!!.availability)
    }

    @Test fun changedQuarantineIdentityCannotBePurged() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        dao.compareAvailability(id, "READY", "CLEANUP_FENCED")
        val quarantine = root.parent.resolveSibling("managed-cleanup").resolve(id)
        Files.createDirectories(quarantine)
        Files.write(quarantine.resolve("foreign.txt"), "keep".toByteArray())
        assertThrows(IllegalStateException::class.java) { cleanup().cleanup(id) { false } }
        assertEquals("keep", String(Files.readAllBytes(quarantine.resolve("foreign.txt"))))
        org.junit.Assert.assertTrue(Files.exists(root))
        assertEquals("CLEANUP_FENCED", repo.find(id)!!.availability)
    }

    @Test fun missingFencedSourceCannotBeMisreportedAsCompletedCleanup() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        dao.compareAvailability(id, "READY", "CLEANUP_FENCED")
        Files.delete(root)
        assertThrows(java.io.IOException::class.java) { cleanup().cleanup(id) { false } }
        assertEquals("CLEANUP_FENCED", repo.find(id)!!.availability)
    }

    @Test fun explicitSelectionCannotRetireCleanupFence() {
        val repo = repository()
        val external = repo.register("PATH", "scope:external:project", "inode", 1)
        dao.compareAvailability(external.id, "READY", "CLEANUP_FENCED")
        assertThrows(IllegalArgumentException::class.java) {
            repo.register("PATH", "scope:external:project", "inode", 2)
        }
        assertEquals("CLEANUP_FENCED", repo.find(external.id)!!.availability)
    }

    @Test fun interruptedPurgeResumesOnlyAfterExplicitRequest() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        Files.write(root.resolve("remaining.txt"), "keep until resumed".toByteArray())
        val quarantine = root.parent.resolveSibling("managed-cleanup").resolve(id)
        Files.createDirectories(quarantine.parent)
        Files.move(root, quarantine)
        dao.compareAvailability(id, "READY", "CLEANUP_PURGING")
        cleanup()
        org.junit.Assert.assertTrue(Files.exists(quarantine.resolve("remaining.txt")))
        cleanup().cleanup(id) { false }
        assertFalse(Files.exists(quarantine))
        assertEquals("DELETED", repo.find(id)!!.availability)
    }

    @Test fun purgeReceiptRecoversAfterDirectoryRemoval() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        dao.compareAvailability(id, "READY", "CLEANUP_PURGING")
        Files.delete(root)
        cleanup().cleanup(id) { false }
        assertEquals("DELETED", repo.find(id)!!.availability)
    }

    @Test fun lateResolutionFailurePreservesCleanupPhase() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        dao.compareAvailability(id, "READY", "CLEANUP_FENCED")
        repo.markUnavailable(id)
        assertEquals("CLEANUP_FENCED", repo.find(id)!!.availability)
        cleanup().cleanup(id) { false }
        repo.markUnavailable(id)
        assertEquals("DELETED", repo.find(id)!!.availability)
    }

    @Test fun managedResolutionFailureCannotOverwriteConcurrentFence() {
        val repo = repository()
        val id = FileScopePath.fromModelReference(repo.defaultDirectory("owner", 1)).scopeId
        val root = repo.managedDirectory(id)
        dao.afterFind = {
            dao.compareAvailability(id, "READY", "CLEANUP_FENCED")
            Files.delete(root)
        }
        assertThrows(IllegalStateException::class.java) { repo.managedDirectory(id) }
        assertEquals("CLEANUP_FENCED", repo.find(id)!!.availability)
    }

    private class FakeWorkspaceDao : WorkspaceDao {
        var afterFind: (() -> Unit)? = null
        private val resources = mutableMapOf<String, WorkspaceEntity>()
        private val bindings = mutableMapOf<String, SessionWorkspaceEntity>()
        private val requests = mutableMapOf<String, ModelCallWorkspaceEntity>()

        override fun insert(workspace: WorkspaceEntity) {
            check(resources[workspace.id] == null && byIdentity(workspace.identityKey) == null)
            resources[workspace.id] = workspace
        }

        override fun find(id: String): WorkspaceEntity? {
            val snapshot = resources[id]
            val callback = afterFind
            afterFind = null
            callback?.invoke()
            return snapshot
        }

        override fun byIdentity(identity: String) = resources.values.singleOrNull { it.identityKey == identity }

        override fun ownedBy(sessionId: String) = resources.values.firstOrNull { it.ownerSessionId == sessionId }

        override fun releaseOwnership(sessionId: String): Int {
            val owned = resources.values.filter { it.ownerSessionId == sessionId }
            owned.forEach { resources[it.id] = it.copy(ownerSessionId = null) }
            return owned.size
        }

        override fun list() = resources.values.toList()

        override fun updateAvailability(
            id: String,
            availability: String,
            witness: String?,
        ): Int {
            val row = resources[id] ?: return 0
            resources[id] = row.copy(availability = availability, witness = witness)
            return 1
        }

        override fun compareAvailability(
            id: String,
            expected: String,
            next: String,
        ): Int {
            val row = resources[id]?.takeIf { it.availability == expected } ?: return 0
            resources[id] = row.copy(availability = next)
            return 1
        }

        override fun retire(id: String): Int {
            val row = resources[id] ?: return 0
            resources[id] =
                row.copy(
                    ownerSessionId = null,
                    availability = "UNAVAILABLE",
                    identityKey = row.identityKey + ":retired:" + id,
                )
            return 1
        }

        override fun bind(binding: SessionWorkspaceEntity) {
            bindings[binding.sessionId] = binding
        }

        override fun binding(sessionId: String) = bindings[sessionId]

        override fun references(workspaceId: String) = bindings.values.count { it.workspaceId == workspaceId }

        override fun retentionReferences(
            workspaceId: String,
            referencePrefix: String,
        ) = com.helix.core.storage.entity.WorkspaceReferences(
            ownerSessions = 0,
            sessionBindings = references(workspaceId),
            modelRequests = requests.values.count { it.workspaceId == workspaceId },
            artifacts = 0,
        )

        override fun recordRequest(binding: ModelCallWorkspaceEntity) {
            check(requests[binding.modelCallId] == null)
            requests[binding.modelCallId] = binding
        }

        override fun requestBinding(modelCallId: String) = requests[modelCallId]
    }
}
