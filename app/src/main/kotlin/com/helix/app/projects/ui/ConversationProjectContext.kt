package com.helix.app.projects.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import com.helix.app.R
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService
import com.helix.app.projects.ProjectService
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.workspace.FileScopePath

/** Project and cwd are visible together, while existing membership keeps its independent cwd. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun ConversationProjectContext(
    service: ProjectService,
    files: FileManagerService,
    chat: ChatService,
    screen: ChatScreenState,
    onOpen: (String) -> Unit,
) {
    val session = screen.openSessionId ?: return
    val projects by service.projects.collectAsStateWithLifecycle(emptyList())
    val memberships by service.memberships.collectAsStateWithLifecycle(emptyList())
    val current =
        if (screen.isDraft) {
            screen.projectId
        } else {
            memberships
                .firstOrNull { it.sessionId == session }
                ?.projectId
        }
    val project = projects.firstOrNull { it.id == current }
    var selecting by remember(session) { mutableStateOf(false) }
    var creating by remember(session) { mutableStateOf(false) }
    val actions = rememberProjectActions()

    fun choose(id: String?) {
        actions.run {
            check(chat.selectConversationProject(session, id)) { "PROJECT_CHANGED" }
            selecting = false
        }
    }
    Column {
        TextButton(
            { selecting = true },
            enabled = !screen.isSending,
            modifier = Modifier.testTag("conversation-project"),
        ) {
            Text(project?.name ?: stringResource(R.string.project_none))
        }
        val path = screen.directoryRef?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        Text(
            if (path == null) {
                stringResource(R.string.chat_directory_none)
            } else {
                stringResource(
                    R.string.project_working_directory,
                    path.relativePath.ifEmpty {
                        project?.takeIf { it.workspaceId == path.scopeId }?.name
                            ?: path.scopeId
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("conversation-directory"),
        )
    }
    if (selecting) {
        AlertDialog(
            onDismissRequest = { if (!actions.busy) selecting = false },
            title = { Text(stringResource(R.string.nav_projects)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).indicatedVerticalScroll(rememberScrollState())) {
                    if (!screen.isNewConversation) Text(stringResource(R.string.project_membership_hint))
                    current?.let { id ->
                        TextButton({
                            selecting = false
                            onOpen(id)
                        }) { Text(stringResource(R.string.project_open)) }
                    }
                    TextButton(
                        { choose(null) },
                        enabled = !actions.busy,
                        modifier = Modifier.testTag("conversation-project-none"),
                    ) {
                        Text(stringResource(R.string.project_none))
                    }
                    projects.filter { it.archivedAt == null }.forEach { item ->
                        TextButton({
                            choose(item.id)
                        }, enabled = !actions.busy, modifier = Modifier.testTag("conversation-project-${item.id}")) {
                            Text(item.name)
                        }
                    }
                    actions.error?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                TextButton(
                    { creating = true },
                    enabled = !actions.busy,
                    modifier = Modifier.testTag("conversation-project-create"),
                ) {
                    Text(stringResource(R.string.project_create))
                }
            },
            dismissButton = {
                TextButton(
                    { selecting = false },
                    enabled = !actions.busy,
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (creating) {
        ProjectEditor(service, files, null, { creating = false }) { id ->
            creating = false
            choose(id)
        }
    }
}
