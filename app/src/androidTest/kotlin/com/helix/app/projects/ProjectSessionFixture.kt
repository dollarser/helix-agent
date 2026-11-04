package com.helix.app.projects

import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import java.util.UUID

/** Persistent synthetic membership for storage/memory tests, without invoking the chat UI. */
internal fun createProjectSessionFixture(
    storage: HelixStorage,
    projectId: String,
    providerId: String?,
    modelId: String?,
): String {
    val project = requireNotNull(storage.projects.find(projectId))
    val id = UUID.randomUUID().toString()
    storage.withTransaction {
        storage.sessions.create(
            id,
            project.name,
            providerId,
            modelId,
            System.currentTimeMillis(),
            FileScopePath(project.workspaceId, project.relativePath).toModelReference(),
        )
        storage.projects.assign(id, projectId)
    }
    return id
}
