package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService

/**
 * The task-artifact view of the Tasks dashboard (HXA-202 slice 2): the REAL files the task's
 * tools wrote, read by ownership — the turn's own `turnId` rows for a turn, the union of every
 * turn bound to the goal for a goal. The global recent window is never used here, so a task's
 * files cannot be lost behind its truncation.
 *
 * Reading is a read: opening this dialog, or the file view stacked over it ([ArtifactFileDialog],
 * which re-checks the file at open and returns to the source session), starts, continues or
 * mutates nothing — the 203 delivery operations stay out of this task.
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun TaskArtifactsDialog(
    service: ChatService,
    fileManager: FileManagerService,
    turnId: String?,
    goalId: String?,
    onOpenSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember(turnId, goalId) { mutableStateOf<List<ArtifactRowUi>?>(null) }
    var selected by remember { mutableStateOf<ArtifactRowUi?>(null) }
    LaunchedEffect(turnId, goalId) {
        rows =
            if (turnId != null) {
                service.taskArtifactsForTurn(turnId)
            } else {
                service.taskArtifactsForGoal(requireNotNull(goalId))
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_artifacts)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("tasks-artifacts-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val list = rows
                if (list == null) {
                    Text(stringResource(R.string.egress_loading))
                } else if (list.isEmpty()) {
                    Text(
                        stringResource(R.string.tasks_artifacts_empty),
                        modifier = Modifier.testTag("tasks-artifacts-empty"),
                    )
                } else {
                    list.forEach { row ->
                        ArtifactFileRowView(row, onOpen = { selected = row })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("tasks-artifacts-close")) {
                Text(stringResource(R.string.goal_close))
            }
        },
    )
    selected?.let { row ->
        ArtifactFileDialog(
            fileManager,
            row,
            onOpenSession = { onOpenSession(row.sessionId) },
            onDismiss = { selected = null },
        )
    }
}
