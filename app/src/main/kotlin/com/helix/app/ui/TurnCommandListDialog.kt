package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.proot.CommandEntry
import com.helix.core.model.ToolCallState

/**
 * HXA-194: the task page's command entry (任务页入口). Lists the turn's Linux command
 * calls — the commands already visible in the request parameters — with each call's
 * settled state, and opens the command details page per call. A pure read: opening this
 * list or the page from it starts or continues nothing.
 */
@Composable
@Suppress("FunctionName")
internal fun TurnCommandListDialog(
    service: ChatService,
    turnId: String,
    onOpenCommandDetail: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember(turnId) { mutableStateOf<List<CommandEntry>?>(null) }
    LaunchedEffect(turnId) {
        entries = service.turnCommands(turnId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_turn_command)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (entries == null) {
                    Text(stringResource(R.string.tasks_commands_loading))
                } else if (entries!!.isEmpty()) {
                    Text(stringResource(R.string.tasks_commands_empty))
                } else {
                    entries!!.forEach { entry ->
                        Column(Modifier.testTag("turn-command-${entry.callId}")) {
                            Text(
                                entry.commandText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(commandStateTextRes(entry.state)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(
                                { onOpenCommandDetail(turnId, entry.callId) },
                                Modifier.testTag("turn-command-detail-${entry.callId}"),
                            ) { Text(stringResource(R.string.tasks_command_detail)) }
                            Text(
                                stringResource(R.string.tasks_command_call_id, entry.callId),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.goal_close)) } },
    )
}

/** The call's persisted state as the list label (same states the tool timeline shows). */
private fun commandStateTextRes(state: String): Int =
    when (runCatching { ToolCallState.valueOf(state) }.getOrNull()) {
        ToolCallState.PENDING -> R.string.tool_state_processing
        ToolCallState.AWAITING_APPROVAL -> R.string.tool_state_awaiting_approval
        ToolCallState.RUNNING -> R.string.tool_state_running
        ToolCallState.NEEDS_REVIEW -> R.string.tool_state_needs_review
        ToolCallState.INTERRUPTED -> R.string.tool_state_interrupted
        ToolCallState.COMPLETED -> R.string.tool_state_completed
        ToolCallState.FAILED -> R.string.tool_state_failed
        ToolCallState.CANCELLED -> R.string.tool_state_cancelled
        ToolCallState.DENIED -> R.string.tool_state_denied
        null -> R.string.tool_state_unknown
    }
