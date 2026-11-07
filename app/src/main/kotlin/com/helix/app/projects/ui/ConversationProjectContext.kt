package com.helix.app.projects.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.projects.ProjectService
import com.helix.feature.conversation.application.ChatService
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.ui.shared.indicatedVerticalScroll
import com.helix.ui.shared.R as SharedR

/** Project selection belongs to session settings; directory controls have their own section. */
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
    com.helix.app.ui.SettingsEntryRow(
        stringResource(R.string.nav_projects),
        R.drawable.ic_drawer_work,
        { selecting = true },
        Modifier.testTag("conversation-project"),
        project?.name ?: stringResource(R.string.project_none),
        enabled = !screen.isSending,
    )

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
                        }, enabled = !actions.busy) { Text(stringResource(R.string.project_open)) }
                    }
                    ProjectChoice(
                        stringResource(R.string.project_none),
                        current == null,
                        !actions.busy,
                        "conversation-project-none",
                    ) { choose(null) }
                    projects.filter { it.archivedAt == null }.forEach { item ->
                        ProjectChoice(item.name, item.id == current, !actions.busy, "conversation-project-${item.id}") {
                            choose(item.id)
                        }
                    }
                    actions.error?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        selecting = false
                        creating = true
                    },
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
                ) { Text(stringResource(SharedR.string.common_cancel)) }
            },
        )
    }
    if (creating) {
        ProjectEditor(service, files, null, {
            creating = false
            selecting = true
        }) { id ->
            creating = false
            selecting = true
            choose(id)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ProjectChoice(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(title, Modifier.weight(1f))
    }
}
