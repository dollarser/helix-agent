package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.infrastructure.conversation.ConversationWorkspaceDetails
import com.helix.app.proot.CommandEntry
import com.helix.core.model.ToolCallState
import com.helix.ui.shared.indicatedVerticalScroll
import kotlinx.coroutines.CancellationException
import com.helix.feature.conversation.R as ConversationR

/**
 * HXA-194: the task page's command entry (任务页入口). Lists the turn's Linux command
 * calls — the commands already visible in the request parameters — with each call's
 * settled state, and opens the command details page per call. A pure read: opening this
 * list or the page from it starts or continues nothing.
 */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // A failed local read remains retryable; never an empty list.
internal fun TurnCommandListDialog(
    service: ConversationWorkspaceDetails,
    turnId: String,
    onOpenCommandDetail: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember(service, turnId) { mutableStateOf<List<CommandEntry>?>(null) }
    var failed by remember(service, turnId) { mutableStateOf(false) }
    var retry by remember(service, turnId) { mutableIntStateOf(0) }
    LaunchedEffect(service, turnId, retry) {
        try {
            entries = service.turnCommands(turnId)
            failed = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    if (entries == null && !failed) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_turn_command)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .indicatedVerticalScroll(rememberScrollState()),
            ) {
                if (failed) {
                    LocalListReadError { retry++ }
                } else if (entries.orEmpty().isEmpty()) {
                    Text(stringResource(R.string.tasks_commands_empty))
                } else {
                    entries.orEmpty().forEach { entry ->
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
        ToolCallState.PENDING -> ConversationR.string.tool_state_processing
        ToolCallState.AWAITING_APPROVAL -> ConversationR.string.tool_state_awaiting_approval
        ToolCallState.RUNNING -> ConversationR.string.tool_state_running
        ToolCallState.NEEDS_REVIEW -> ConversationR.string.tool_state_needs_review
        ToolCallState.INTERRUPTED -> ConversationR.string.tool_state_interrupted
        ToolCallState.COMPLETED -> ConversationR.string.tool_state_completed
        ToolCallState.FAILED -> ConversationR.string.tool_state_failed
        ToolCallState.CANCELLED -> ConversationR.string.tool_state_cancelled
        ToolCallState.DENIED -> ConversationR.string.tool_state_denied
        null -> ConversationR.string.tool_state_unknown
    }
