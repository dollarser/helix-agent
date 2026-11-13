package com.helix.app.session

import com.helix.core.storage.repository.SessionAccessRepository
import com.helix.feature.conversation.presentation.SessionAccessSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit user grant commands, separate from model invocation and reference selection. */
class SessionAccessService(
    private val repository: SessionAccessRepository,
) {
    suspend fun load(source: String): List<SessionAccessSelection> =
        withContext(Dispatchers.IO) {
            repository.grants(source).map {
                SessionAccessSelection(it.targetSessionId, it.readHistory, it.manageMetadata, it.delegateTasks)
            }
        }

    suspend fun save(
        source: String,
        selection: SessionAccessSelection,
    ) = withContext(Dispatchers.IO) {
        repository.setGrant(
            source,
            selection.targetSessionId,
            selection.readHistory,
            selection.manageMetadata,
            selection.delegateTasks,
        )
        Unit
    }
}
