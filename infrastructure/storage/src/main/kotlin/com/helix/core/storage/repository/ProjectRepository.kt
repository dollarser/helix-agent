package com.helix.core.storage.repository

import com.helix.core.storage.dao.ProjectDao
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.storage.entity.ProjectSessionEntity
import com.helix.core.workspace.FileScopePath

/** Explicit organization only. Never grants permissions or moves files when joining a project. */
@Suppress("TooManyFunctions") // One transactional project aggregate, including live tool ownership lookup.
class ProjectRepository(
    private val dao: ProjectDao,
    private val transaction: (() -> Unit) -> Unit,
    private val directoryAvailable: (FileScopePath) -> Boolean,
    private val audit: (String, String) -> Unit,
) {
    val projects get() = dao.observe()
    val memberships get() = dao.observeMemberships()

    fun forWorkspace(id: String): List<ProjectEntity> = dao.forWorkspace(id)

    fun find(id: String): ProjectEntity? = dao.find(id)

    fun forSession(id: String): ProjectEntity? = dao.forSession(id)

    fun forTool(
        sessionId: String,
        turnId: String,
        toolCallId: String,
    ): ProjectEntity? = dao.forTool(sessionId, turnId, toolCallId)

    fun sessionIds(id: String): List<String> = dao.sessionIds(id)

    fun create(project: ProjectEntity) =
        transaction {
            require(project.id.matches(Regex("project-[a-f0-9-]{36}")))
            validate(project)
            dao.insert(project)
            audit(project.id, "project.created")
        }

    fun update(project: ProjectEntity) =
        transaction {
            validate(project)
            check(
                dao.update(
                    project.id,
                    project.revision,
                    project.name.trim(),
                    project.description,
                    project.instructions,
                    project.workspaceId,
                    project.relativePath,
                    project.updatedAt,
                ) == 1,
            ) { "PROJECT_CHANGED" }
            audit(project.id, "project.updated")
        }

    fun archive(
        id: String,
        revision: Long,
        archived: Boolean,
        now: Long,
    ) = transaction {
        require(now >= 0)
        check(dao.archive(id, revision, now.takeIf { archived }, now) == 1) { "PROJECT_CHANGED" }
        audit(id, if (archived) "project.archived" else "project.restored")
    }

    fun assign(
        sessionId: String,
        projectId: String?,
    ) = transaction {
        check(!dao.busy(sessionId)) { "PROJECT_SESSION_BUSY" }
        if (projectId == null) {
            dao.unbind(sessionId)
        } else {
            val project = requireNotNull(dao.find(projectId)) { "PROJECT_MISSING" }
            check(project.archivedAt == null) { "PROJECT_ARCHIVED" }
            dao.bind(ProjectSessionEntity(sessionId, projectId))
        }
        audit(sessionId, "project.membership.changed")
    }

    fun delete(
        id: String,
        revision: Long,
    ) = transaction {
        check(dao.sessionIds(id).none(dao::busy)) { "PROJECT_SESSION_BUSY" }
        check(dao.delete(id, revision) == 1) { "PROJECT_CHANGED" }
        audit(id, "project.deleted")
    }

    private fun validate(project: ProjectEntity) {
        require(project.name.isNotBlank() && project.name.length <= 120)
        require(project.description.length <= 2_000 && project.instructions.length <= 16_000)
        require(listOf(project.name, project.description, project.instructions).none { '\u0000' in it })
        require(project.createdAt >= 0 && project.updatedAt >= project.createdAt && project.revision >= 1)
        require(directoryAvailable(FileScopePath(project.workspaceId, project.relativePath))) {
            "PROJECT_DIRECTORY_UNAVAILABLE"
        }
    }
}
