package com.helix.app.projects

import com.helix.core.storage.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal data class ProjectSessionRow(
    val id: String,
    val title: String,
    val archived: Boolean,
)

internal sealed interface ProjectSessionState {
    data object Loading : ProjectSessionState

    data class Ready(
        val rows: List<ProjectSessionRow>,
    ) : ProjectSessionState

    data object Failed : ProjectSessionState
}

/** Derived display state only. Room remains authoritative, and failed reads never look like empty success. */
internal fun projectSessionRows(source: Flow<List<SessionSummary>>): Flow<ProjectSessionState> =
    source
        .map<List<SessionSummary>, ProjectSessionState> { rows ->
            ProjectSessionState.Ready(rows.map { ProjectSessionRow(it.id, it.title, it.archived) })
        }.distinctUntilChanged()
        .onStart { emit(ProjectSessionState.Loading) }
        .catch { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            emit(ProjectSessionState.Failed)
        }.flowOn(Dispatchers.IO)
