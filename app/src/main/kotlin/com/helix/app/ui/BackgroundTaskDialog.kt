package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import com.helix.app.chat.ChatService
import com.helix.core.model.TurnState

/** Viewing and collecting are explicit UI actions, never model input or a replay. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun BackgroundTaskDialog(
    service: ChatService,
    onDismiss: () -> Unit,
) {
    val tasks by service.backgroundTasks.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    if (result != null) {
        TaskResultDialog(service, requireNotNull(result)) { result = null }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_tasks)) },
        text = {
            Column {
                Text(stringResource(R.string.background_tasks_hint))
                Row {
                    listOf(
                        R.string.background_tasks_active,
                        R.string.background_tasks_results,
                        R.string.background_tasks_history,
                    ).forEachIndexed { index, label ->
                        TextButton({ tab = index }, Modifier.testTag("task-tab-$index")) {
                            Text(stringResource(label))
                        }
                    }
                }
                val visible = tasks.filter { if (tab == 0) it.running else !it.running && it.collected == (tab == 2) }
                if (visible.isEmpty()) Text(stringResource(R.string.background_tasks_empty))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { task ->
                        Column(Modifier.testTag("task-${task.id}")) {
                            Text(task.title)
                            Text(
                                stringResource(
                                    if (task.outcome == "USER_PAUSED") {
                                        R.string.goal_state_paused
                                    } else if (task.outcome?.startsWith("BLOCKED(") == true ||
                                        task.outcome?.startsWith("BUDGET_EXHAUSTED(") == true
                                    ) {
                                        R.string.goal_state_blocked
                                    } else {
                                        taskStateLabel(task.state)
                                    },
                                ),
                            )
                            if (task.pauseRequested &&
                                task.running
                            ) {
                                Text(stringResource(R.string.background_task_pausing))
                            }
                            TextButton({
                                service.openSession(task.sessionId)
                                onDismiss()
                            }) {
                                Text(stringResource(R.string.background_task_open))
                            }
                            if (!task.running) {
                                TextButton({ result = task.id }, Modifier.testTag("task-result-${task.id}")) {
                                    Text(stringResource(R.string.background_task_result))
                                }
                            }
                            if (task.running) {
                                Row {
                                    if (task.goalId != null) {
                                        TextButton(
                                            { service.stopTask(task.id, pause = true) },
                                            enabled = !task.pauseRequested,
                                            modifier = Modifier.testTag("task-pause-${task.id}"),
                                        ) { Text(stringResource(R.string.background_task_pause)) }
                                    }
                                    TextButton(
                                        { service.stopTask(task.id) },
                                        Modifier.testTag("task-cancel-${task.id}"),
                                    ) {
                                        Text(stringResource(R.string.background_task_cancel))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.goal_close)) } },
    )
}

internal fun taskStateLabel(state: TurnState): Int =
    when (state) {
        TurnState.COMPLETED -> R.string.goal_state_completed
        TurnState.FAILED -> R.string.goal_state_failed
        TurnState.CANCELLED -> R.string.goal_state_cancelled
        TurnState.INTERRUPTED -> R.string.goal_pause_interrupted
        TurnState.WAITING_APPROVAL -> R.string.goal_state_input
        else -> R.string.goal_state_running
    }
