package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.core.workspace.FileScopePath

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ProjectDetailScreen(
    container: AppContainer,
    id: String,
    onOpenSession: (String) -> Unit,
    onDeleted: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenCommand: (String, String) -> Unit,
    onOpenDraft: () -> Unit = {},
) {
    val service = container.projects ?: return
    val projects by service.projects.collectAsStateWithLifecycle(emptyList())
    val memberships by service.memberships.collectAsStateWithLifecycle(emptyList())
    val project = projects.firstOrNull { it.id == id }
    if (project == null) {
        Text(stringResource(R.string.project_missing))
        return
    }
    val members = memberships.filter { it.projectId == id }.map { it.sessionId }.toSet()
    var tab by remember(id) { mutableStateOf(0) }
    var editing by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    var memoryOpen by remember(id) { mutableStateOf(false) }
    val actions = rememberProjectActions()
    val chat by container.chatService.screen.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("screen-project-detail")) {
        Column(Modifier.padding(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            if (project.description.isNotBlank()) Text(project.description, maxLines = 3)
            if (project.archivedAt != null) Text(stringResource(R.string.project_archived))
            SettingsActions {
                TextButton({ editing = true }, enabled = !actions.busy) {
                    Text(stringResource(R.string.project_settings))
                }
                TextButton({
                    actions.run { service.archive(project, project.archivedAt == null) }
                }, enabled = !actions.busy) {
                    val label =
                        if (project.archivedAt == null) R.string.project_archive else R.string.project_restore
                    Text(stringResource(label))
                }
                TextButton({ memoryOpen = true }) { Text(stringResource(R.string.memory_title)) }
                TextButton({ deleting = true }, enabled = !actions.busy) {
                    Text(stringResource(R.string.project_delete))
                }
            }
            actions.error?.let { Text(stringResource(it)) }
            SettingsActions {
                val tabs =
                    listOf(
                        R.string.nav_sessions,
                        R.string.nav_files,
                        R.string.project_tasks,
                        R.string.project_results,
                    )
                tabs.forEachIndexed { index, label ->
                    TextButton(
                        { tab = index },
                        enabled = tab != index,
                        modifier = Modifier.testTag("project-tab-$index"),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        when (tab) {
            0 -> {
                ProjectSessions(service, id, members, project.archivedAt != null, onNew = {
                    actions.run {
                        val session = service.newSession(id, chat.badge?.providerId, chat.badge?.model)
                        container.chatService.refreshSessions()
                        onOpenSession(session)
                    }
                }, onOpenSession)
            }

            1 -> {
                FilesScreen(
                    container.fileManager,
                    container.safTree,
                    container.featureFiles,
                    initialDirectory = FileScopePath(project.workspaceId, project.relativePath),
                    handoff = FileConversationHandoff(container.chatService) { onOpenDraft() },
                )
            }

            2, 3 -> {
                ProjectHistory(container, members, tab, onOpenSession, onOpenTask, onOpenCommand)
            }
        }
    }
    if (editing) {
        ProjectEditor(service, container.fileManager, project, { editing = false }) { editing = false }
    }
    if (memoryOpen) {
        container.memory?.let { memory ->
            MemoryDialog(memory, null, projectId = id) { memoryOpen = false }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { if (!actions.busy) deleting = false },
            title = { Text(stringResource(R.string.project_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.project_delete_hint))
                    actions.error?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                TextButton({
                    actions.run {
                        service.delete(project)
                        onDeleted()
                    }
                }, enabled = !actions.busy) { Text(stringResource(R.string.project_delete)) }
            },
            dismissButton = {
                TextButton({ deleting = false }, enabled = !actions.busy) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
