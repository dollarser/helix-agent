package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "SwallowedException") // State races are surfaced as a fixed localized reminder error.
internal fun GoalReminderControls(service: ChatService, goal: GoalSummaryUi, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(goal.id) { mutableStateOf(false) }
    var failed by remember(goal.id) { mutableStateOf(false) }
    val enabled = goal.status.state in setOf("RUNNING", "PAUSED") && !busy
    val update: (Long?) -> Unit = { delay ->
        scope.launch {
            busy = true
            failed = false
            try {
                check(service.setGoalReminder(goal.id, delay))
                onChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                failed = true
            } catch (error: IllegalStateException) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    if (goal.status.nextCheckpoint != null) Text(stringResource(R.string.goal_reminder_configured))
    TextButton(enabled = enabled, onClick = {
        update(30 * 60_000L)
    }, modifier = Modifier.testTag("goal-remind-${goal.id}")) {
        Text(stringResource(R.string.goal_remind_later))
    }
    TextButton(
        enabled = enabled && goal.status.nextCheckpoint != null,
        onClick = { update(null) },
        modifier = Modifier.testTag("goal-reminder-clear-${goal.id}"),
    ) {
        Text(stringResource(R.string.goal_reminder_clear))
    }
    if (failed) Text(stringResource(R.string.goal_reminder_sync_failed), color = MaterialTheme.colorScheme.error)
}
