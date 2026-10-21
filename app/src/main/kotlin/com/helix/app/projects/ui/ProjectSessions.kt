package com.helix.app.projects.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.projects.ProjectService
import com.helix.app.projects.ProjectSessionRow
import com.helix.app.projects.ProjectSessionState
import com.helix.app.ui.HelixCard
import com.helix.app.ui.IndicatedLazyColumn
import com.helix.app.ui.SettingsActions

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ProjectSessions(
    service: ProjectService,
    projectId: String,
    members: Set<String>,
    archived: Boolean,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var reload by remember(service, projectId) { mutableStateOf(false) }
    val snapshots = remember(service, reload) { service.sessions }
    val state by snapshots.collectAsStateWithLifecycle(ProjectSessionState.Loading)
    val rows = (state as? ProjectSessionState.Ready)?.rows.orEmpty()
    var adding by remember(service, projectId) { mutableStateOf(false) }
    val actions = rememberProjectActions()
    LaunchedEffect(archived) { if (archived) adding = false }
    val joining = adding && !archived
    Column {
        if (!archived) {
            SettingsActions {
                TextButton(onNew) { Text(stringResource(R.string.project_new_session)) }
                TextButton({
                    adding = !adding
                }, modifier = Modifier.testTag("project-add-session")) {
                    Text(stringResource(if (adding) R.string.common_cancel else R.string.project_add_session))
                }
            }
        }
        actions.error?.let { Text(stringResource(it)) }
        if (state == ProjectSessionState.Failed) {
            TextButton({ reload = !reload }, modifier = Modifier.testTag("project-sessions-retry")) {
                Text(stringResource(R.string.project_sessions_retry))
            }
        }
        if (adding) Text(stringResource(R.string.project_membership_hint))
        val visible = rows.filter { if (joining) it.id !in members && !it.archived else it.id in members }
        IndicatedLazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visible.isEmpty()) {
                item {
                    Text(stringResource(projectSessionsEmptyMessage(state)))
                }
            }
            items(visible, key = { it.id }) { session ->
                ProjectSessionCard(session, joining, actions.busy, onOpen, onJoin = { id ->
                    actions.run {
                        service.assign(id, projectId)
                        adding = false
                    }
                }, onRemove = { id -> actions.run { service.assign(id, null) } })
            }
        }
    }
}

private fun projectSessionsEmptyMessage(state: ProjectSessionState): Int =
    when (state) {
        ProjectSessionState.Loading -> R.string.project_sessions_loading
        ProjectSessionState.Failed -> R.string.project_sessions_failed
        is ProjectSessionState.Ready -> R.string.project_sessions_empty
    }

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun ProjectSessionCard(
    row: ProjectSessionRow,
    joining: Boolean,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onJoin: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    HelixCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(row.title.ifBlank { stringResource(R.string.chat_new_session) })
            if (row.archived) Text(stringResource(R.string.project_archived))
            SettingsActions {
                if (joining) {
                    TextButton(
                        { onJoin(row.id) },
                        enabled = !busy,
                        modifier = Modifier.testTag("project-join-${row.id}"),
                    ) {
                        Text(stringResource(R.string.project_join))
                    }
                } else {
                    TextButton({ onOpen(row.id) }) { Text(stringResource(R.string.project_open_session)) }
                    TextButton({ onRemove(row.id) }, enabled = !busy) {
                        Text(stringResource(R.string.project_remove_session))
                    }
                }
            }
        }
    }
}
