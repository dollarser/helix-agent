package com.helix.core.storage.repository

import com.helix.core.storage.dao.ProjectDao
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.storage.entity.ProjectSessionEntity
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRepositoryTest {
    private val dao = FakeProjects()
    private var directoryReady = true
    private var auditFails = false
    private val repository =
        ProjectRepository(dao, { block ->
            val projects = dao.rows.toMap()
            val members = dao.members.toMap()
            try {
                block()
            } catch (failure: Exception) {
                dao.rows.clear()
                dao.rows.putAll(projects)
                dao.members.clear()
                dao.members.putAll(members)
                throw failure
            }
        }, { directoryReady }, { _, _ -> check(!auditFails) })
    private val a = project("a")
    private val b = project("b")

    @Test fun sameNameProjectsHaveSeparateStableIdentityAndMembership() {
        repository.create(a)
        repository.create(b)
        repository.assign("s", a.id)
        assertEquals(a.id, repository.forSession("s")?.id)
        assertTrue(repository.sessionIds(b.id).isEmpty())
        repository.assign("s", b.id)
        assertTrue(repository.sessionIds(a.id).isEmpty())
        assertEquals(listOf("s"), repository.sessionIds(b.id))
    }

    @Test fun emptyMembershipNeverMeansAllSessions() {
        repository.create(a)
        assertTrue(repository.sessionIds(a.id).isEmpty())
        assertNull(repository.forSession("unassigned"))
    }

    @Test fun membershipCanBeRemovedWithoutDeletingProject() {
        repository.create(a)
        repository.assign("s", a.id)
        repository.assign("s", null)
        assertNull(repository.forSession("s"))
        assertNotNull(repository.find(a.id))
    }

    @Test fun busySessionCannotMoveOrLeave() {
        repository.create(a)
        repository.create(b)
        repository.assign("s", a.id)
        dao.active += "s"
        assertThrows(IllegalStateException::class.java) { repository.assign("s", b.id) }
        assertThrows(IllegalStateException::class.java) { repository.assign("s", null) }
        assertEquals(a.id, repository.forSession("s")?.id)
    }

    @Test fun busyProjectCannotBeDeleted() {
        repository.create(a)
        repository.assign("s", a.id)
        dao.active += "s"
        assertThrows(IllegalStateException::class.java) { repository.delete(a.id, 1) }
        assertNotNull(repository.find(a.id))
    }

    @Test fun archivePreservesMembershipButRefusesNewMembership() {
        repository.create(a)
        repository.assign("s", a.id)
        repository.archive(a.id, 1, true, 3)
        assertEquals(a.id, repository.forSession("s")?.id)
        assertThrows(IllegalStateException::class.java) { repository.assign("other", a.id) }
        repository.archive(a.id, 2, false, 4)
        repository.assign("other", a.id)
        assertEquals(2, repository.sessionIds(a.id).size)
    }

    @Test fun staleEditorCannotOverwriteNewInstructionsOrDeleteProject() {
        repository.create(a)
        repository.update(a.copy(instructions = "new", updatedAt = 3))
        assertThrows(IllegalStateException::class.java) { repository.update(a.copy(instructions = "stale")) }
        assertThrows(IllegalStateException::class.java) { repository.delete(a.id, 1) }
        assertEquals("new", repository.find(a.id)?.instructions)
    }

    @Test fun deleteRemovesMembershipOnlyAndOtherProjectsRemain() {
        repository.create(a)
        repository.create(b)
        repository.assign("s", a.id)
        repository.delete(a.id, 1)
        assertNull(repository.find(a.id))
        assertNull(repository.forSession("s"))
        assertNotNull(repository.find(b.id))
    }

    @Test fun unavailableFolderFailsWithoutPublishingProject() {
        directoryReady = false
        assertThrows(IllegalArgumentException::class.java) { repository.create(a) }
        assertNull(repository.find(a.id))
    }

    @Test fun invalidContentAndIdentityAreRejected() {
        listOf(
            a.copy(name = " "),
            a.copy(id = "path-derived"),
            a.copy(instructions = "x".repeat(16_001)),
            a.copy(description = "\u0000"),
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { repository.create(it) }
        }
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun failedAuditRollsBackMutation() {
        repository.create(a)
        auditFails = true
        assertThrows(IllegalStateException::class.java) { repository.update(a.copy(name = "changed")) }
        assertEquals(a, repository.find(a.id))
        assertThrows(IllegalStateException::class.java) { repository.assign("s", a.id) }
        assertNull(repository.forSession("s"))
    }

    private fun project(suffix: String) =
        ProjectEntity(
            "project-00000000-0000-0000-0000-00000000000$suffix",
            "Same name",
            "",
            "",
            "ws",
            "",
            1,
            1,
            1,
            null,
        )
}

private class FakeProjects : ProjectDao {
    val rows = linkedMapOf<String, ProjectEntity>()
    val members = linkedMapOf<String, String>()
    val active = mutableSetOf<String>()

    override fun observe() = flowOf(rows.values.toList())

    override fun observeMemberships() = flowOf(members.map { ProjectSessionEntity(it.key, it.value) })

    override fun forWorkspace(workspaceId: String) = rows.values.filter { it.workspaceId == workspaceId }

    override fun find(id: String) = rows[id]

    override fun forSession(sessionId: String) = members[sessionId]?.let(rows::get)

    override fun forTool(
        sessionId: String,
        turnId: String,
        toolCallId: String,
    ): ProjectEntity? = null

    override fun sessionIds(projectId: String) = members.filterValues { it == projectId }.keys.toList()

    override fun insert(project: ProjectEntity) {
        check(rows.putIfAbsent(project.id, project) == null)
    }

    @Suppress("LongParameterList")
    override fun update(
        id: String,
        revision: Long,
        name: String,
        description: String,
        instructions: String,
        workspaceId: String,
        relativePath: String,
        now: Long,
    ): Int {
        val row = rows[id]?.takeIf { it.revision == revision } ?: return 0
        rows[id] =
            row.copy(
                name = name,
                description = description,
                instructions = instructions,
                workspaceId = workspaceId,
                relativePath = relativePath,
                updatedAt = now,
                revision =
                    revision + 1,
            )
        return 1
    }

    override fun archive(
        id: String,
        revision: Long,
        archivedAt: Long?,
        now: Long,
    ): Int {
        val row = rows[id]?.takeIf { it.revision == revision } ?: return 0
        rows[id] = row.copy(archivedAt = archivedAt, updatedAt = now, revision = revision + 1)
        return 1
    }

    override fun delete(
        id: String,
        revision: Long,
    ): Int {
        if (rows[id]?.revision != revision) return 0
        rows.remove(id)
        members.entries.removeAll { it.value == id }
        return 1
    }

    override fun bind(membership: ProjectSessionEntity) {
        members[membership.sessionId] = membership.projectId
    }

    override fun unbind(sessionId: String) {
        members.remove(sessionId)
    }

    override fun busy(sessionId: String) = sessionId in active
}
