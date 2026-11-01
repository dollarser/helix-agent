package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.HumanHandoffState
import com.helix.app.chat.HumanHandoffUi
import com.helix.core.model.TurnState
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun HumanControlPanel(
    service: ChatService,
    screen: ChatScreenState,
    turnId: String,
) {
    val session = screen.openSessionId ?: return
    val state = (screen.turns + listOfNotNull(screen.activeTurn)).lastOrNull { it.id == turnId }?.state
    var revision by remember { mutableIntStateOf(0) }
    var handoff by remember(turnId) { mutableStateOf<HumanHandoffUi?>(null) }
    var busy by remember(turnId) { mutableStateOf(false) }
    var failed by remember(turnId) { mutableStateOf(false) }
    var pending by remember(turnId) { mutableStateOf(false) }
    var note by rememberSaveable(turnId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session, turnId, state, screen.activeTurn?.id, revision) {
        handoff = service.handoffPresentation(turnId)
    }
    val current = handoff
    Column(Modifier.testTag("human-control-$turnId")) {
        if (current == null && canTakeOver(state)) {
            TextButton(onClick = { service.takeOverTask(session, turnId) }, enabled = !busy) {
                Text(stringResource(R.string.human_take_over))
            }
        } else if (current?.state == HumanHandoffState.REQUESTED) {
            Text(stringResource(R.string.human_target_notice), style = MaterialTheme.typography.bodySmall)
            if (!current.canReturn) {
                Text(stringResource(handoffBlockedLabel(screen.activeTurn?.id == turnId)))
            } else {
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text(stringResource(R.string.human_note)) },
                    enabled = !busy,
                )
                FlowRow {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val outcome = service.returnHumanControl(session, turnId, false, note).await()
                                pending = outcome == ChatSubmissionOutcome.PendingConfirmation
                                failed = !handBackAccepted(outcome)
                                revision++
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.human_return)) }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                service.returnHumanControl(session, turnId, true, note).await()
                                revision++
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.human_finished)) }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                service.dismissHumanControl(session, turnId).await()
                                revision++
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.human_cancel)) }
                }
            }
        }
        val decisionLabel =
            when (current?.state) {
                HumanHandoffState.FINISHED -> R.string.human_state_finished
                HumanHandoffState.CANCELLED -> R.string.human_state_cancelled
                HumanHandoffState.SUBMITTED -> R.string.human_state_submitted
                else -> null
            }
        decisionLabel?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        if (pending) Text(stringResource(R.string.human_return_pending))
        if (failed) Text(stringResource(R.string.human_return_unconfirmed), color = MaterialTheme.colorScheme.error)
    }
}

private fun canTakeOver(state: TurnState?): Boolean =
    state != null && !state.isTerminal && state != TurnState.CANCELLING

private fun handBackAccepted(outcome: ChatSubmissionOutcome): Boolean =
    outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued ||
        outcome == ChatSubmissionOutcome.PendingConfirmation

private fun handoffBlockedLabel(latest: Boolean): Int =
    if (latest) R.string.human_wait_settlement else R.string.human_old_control
