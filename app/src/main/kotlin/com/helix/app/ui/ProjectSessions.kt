package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.projects.ProjectService
import com.helix.core.storage.entity.SessionEntity

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
    var rows by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val actions = rememberProjectActions()
    LaunchedEffect(members, adding) { actions.run { rows = service.sessions() } }
    Column {
        if (!archived) {
            SettingsActions {
                TextButton(onNew) { Text(stringResource(R.string.project_new_session)) }
                TextButton({
                    adding = !adding
                }) { Text(stringResource(if (adding) R.string.common_cancel else R.string.project_add_session)) }
            }
        }
        actions.error?.let { Text(stringResource(it)) }
        if (adding) Text(stringResource(R.string.project_membership_hint))
        val visible = rows.filter { if (adding) it.id !in members && it.archivedAt == null else it.id in members }
        IndicatedLazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visible.isEmpty()) item { Text(stringResource(R.string.project_sessions_empty)) }
            items(visible, key = { it.id }) { session ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(session.title)
                        if (session.archivedAt != null) Text(stringResource(R.string.project_archived))
                        SettingsActions {
                            if (adding) {
                                TextButton({
                                    actions.run {
                                        service.assign(session.id, projectId)
                                        adding = false
                                    }
                                }, enabled = !actions.busy) { Text(stringResource(R.string.project_join)) }
                            } else {
                                TextButton(
                                    { onOpen(session.id) },
                                ) { Text(stringResource(R.string.project_open_session)) }
                                TextButton({
                                    actions.run {
                                        service.assign(session.id, null)
                                    }
                                }, enabled = !actions.busy) { Text(stringResource(R.string.project_remove_session)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
