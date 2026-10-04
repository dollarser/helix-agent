package com.helix.app.projects

import com.helix.app.chat.ArtifactQuery
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.BackgroundTaskQuery
import com.helix.app.chat.BackgroundTaskUi
import com.helix.core.storage.HelixStorage

internal data class ProjectRecords(
    val tasks: List<BackgroundTaskUi> = emptyList(),
    val files: List<ArtifactRowUi> = emptyList(),
)

/** Full member-owned history, independent of the global dashboard's recent window. */
internal fun readProjectRecords(
    storage: HelixStorage,
    members: Set<String>,
): ProjectRecords {
    var result = ProjectRecords()
    storage.withTransaction {
        result =
            ProjectRecords(
                BackgroundTaskQuery(storage).read(members),
                members.flatMap(ArtifactQuery(storage)::forSession),
            )
    }
    return result
}
