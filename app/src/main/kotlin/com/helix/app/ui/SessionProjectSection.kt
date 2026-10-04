package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.projects.ProjectService

@Composable
@Suppress("FunctionName")
internal fun SessionProjectSection(
    service: ProjectService,
    sessionId: String?,
    onOpen: (String) -> Unit,
) {
    if (sessionId == null) return
    val projects by service.projects.collectAsStateWithLifecycle(emptyList())
    val memberships by service.memberships.collectAsStateWithLifecycle(emptyList())
    val current = memberships.firstOrNull { it.sessionId == sessionId }?.projectId
    var selecting by remember(sessionId) { mutableStateOf(false) }
    val actions = rememberProjectActions()
    SettingsGroup {
        Text(stringResource(R.string.nav_projects))
        Text(projects.firstOrNull { it.id == current }?.name ?: stringResource(R.string.project_none))
        SettingsActions {
            current?.let { TextButton({ onOpen(it) }) { Text(stringResource(R.string.project_open)) } }
            TextButton({ selecting = !selecting }) { Text(stringResource(R.string.project_change)) }
        }
        if (selecting) {
            Text(stringResource(R.string.project_membership_hint))
            TextButton({
                actions.run {
                    service.assign(sessionId, null)
                    selecting = false
                }
            }, enabled = !actions.busy) { Text(stringResource(R.string.project_none)) }
            projects.filter { it.archivedAt == null }.forEach { project ->
                TextButton({
                    actions.run {
                        service.assign(sessionId, project.id)
                        selecting = false
                    }
                }, enabled = !actions.busy) { Text(project.name) }
            }
        }
        actions.error?.let { Text(stringResource(it)) }
    }
}
