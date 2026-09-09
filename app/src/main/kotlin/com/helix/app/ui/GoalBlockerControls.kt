package com.helix.app.ui

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
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "SwallowedException")
internal fun GoalBlockerControls(
    service: ChatService,
    goal: GoalSummaryUi,
    onChanged: () -> Unit,
) {
    if (goal.status.state != "BLOCKED") return
    var busy by remember(goal.id) { mutableStateOf(false) }
    var unchanged by remember(goal.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label =
        when (goal.status.outcome) {
            "BLOCKED(MODEL_REPORTED)" -> R.string.goal_blocked_model
            "BLOCKED(CONTEXT_WINDOW_LIMIT)" -> R.string.goal_blocked_context
            "BLOCKED(NEEDS_REVIEW)" -> R.string.goal_blocked_review
            else -> R.string.goal_blocked_budget
        }
    Text(stringResource(label))
    TextButton(
        enabled = !busy,
        modifier = Modifier.testTag("goal-recheck-${goal.id}"),
        onClick = {
            scope.launch {
                busy = true
                try {
                    unchanged = !service.recheckGoalBlocker(goal.id)
                    onChanged()
                } catch (_: IllegalArgumentException) {
                    unchanged = true
                } finally {
                    busy = false
                }
            }
        },
    ) { Text(stringResource(R.string.goal_blocked_recheck)) }
    if (unchanged) Text(stringResource(R.string.goal_blocked_unchanged))
}
