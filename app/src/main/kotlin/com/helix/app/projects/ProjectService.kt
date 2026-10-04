package com.helix.app.projects

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** User-driven project facade. All persistence and directory validation stay off the UI thread. */
class ProjectService(
    private val storage: HelixStorage,
    private val bindDirectory: (String) -> String,
) {
    val projects get() = storage.projects.projects
    val memberships get() = storage.projects.memberships

    suspend fun save(
        existing: ProjectEntity?,
        name: String,
        description: String,
        instructions: String,
        directory: String?,
    ): String =
        withContext(Dispatchers.IO) {
            require(
                name.isNotBlank() && name.length <= 120 &&
                    description.length <= 2_000 && instructions.length <= 16_000,
            )
            val id = existing?.id ?: "project-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            val reference =
                if (directory == null) {
                    existing?.let { FileScopePath(it.workspaceId, it.relativePath).toModelReference() }
                        ?: storage.workspaces.defaultDirectory("project:$id", now)
                } else {
                    bindDirectory(directory)
                }
            val path = FileScopePath.fromModelReference(reference)
            val record =
                ProjectEntity(
                    id,
                    name.trim(),
                    description,
                    instructions,
                    path.scopeId,
                    path.relativePath,
                    existing?.revision ?: 1,
                    existing?.createdAt ?: now,
                    now,
                    existing?.archivedAt,
                )
            if (existing == null) storage.projects.create(record) else storage.projects.update(record)
            id
        }

    suspend fun archive(
        project: ProjectEntity,
        archived: Boolean,
    ) = withContext(Dispatchers.IO) {
        storage.projects.archive(project.id, project.revision, archived, System.currentTimeMillis())
    }

    suspend fun delete(project: ProjectEntity) =
        withContext(Dispatchers.IO) {
            storage.projects.delete(project.id, project.revision)
        }

    suspend fun assign(
        sessionId: String,
        projectId: String?,
    ) = withContext(Dispatchers.IO) {
        storage.projects.assign(sessionId, projectId)
    }

    suspend fun sessions() = withContext(Dispatchers.IO) { storage.sessions.list() }

    internal suspend fun records(members: Set<String>) =
        withContext(Dispatchers.IO) {
            readProjectRecords(storage, members)
        }

    suspend fun newSession(
        projectId: String,
        providerId: String?,
        modelId: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val project = requireNotNull(storage.projects.find(projectId))
            check(project.archivedAt == null) { "PROJECT_ARCHIVED" }
            val directory = bindDirectory(FileScopePath(project.workspaceId, project.relativePath).toModelReference())
            val id = UUID.randomUUID().toString()
            storage.withTransaction {
                check(storage.projects.find(projectId)?.revision == project.revision) { "PROJECT_CHANGED" }
                storage.sessions.create(id, project.name, providerId, modelId, System.currentTimeMillis(), directory)
                storage.projects.assign(id, projectId)
            }
            id
        }
}
