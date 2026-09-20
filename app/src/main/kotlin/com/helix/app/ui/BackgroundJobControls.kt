package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.proot.BackgroundJobAction
import com.helix.app.proot.BackgroundJobActionOutcome
import com.helix.app.proot.BackgroundJobActionUi
import com.helix.app.proot.BackgroundJobUi

@Composable
@Suppress("FunctionName")
internal fun BackgroundJobControls(
    job: BackgroundJobUi,
    state: BackgroundJobActionUi?,
    onAction: (BackgroundJobUi, BackgroundJobAction) -> Unit,
) {
    Column {
        if (job.settlementPending) {
            Row {
                BackgroundJobAction.values().forEach { action ->
                    TextButton(
                        onClick = { onAction(job, action) },
                        enabled = state?.busy != true,
                        modifier = Modifier.testTag("tasks-job-${action.name.lowercase()}-${job.callId}"),
                    ) { Text(stringResource(action.label())) }
                }
            }
        }
        if (state?.callId == job.callId) {
            val label = if (state.busy) R.string.tasks_job_action_busy else state.outcome?.label()
            label?.let { Text(stringResource(it), Modifier.testTag("tasks-job-action-result-${job.callId}")) }
        }
    }
}

private fun BackgroundJobAction.label() =
    when (this) {
        BackgroundJobAction.QUERY -> R.string.tasks_job_query
        BackgroundJobAction.CANCEL -> R.string.tasks_job_cancel
        BackgroundJobAction.COLLECT -> R.string.tasks_job_collect
    }

private fun BackgroundJobActionOutcome.label() =
    when (this) {
        BackgroundJobActionOutcome.ACTIVE -> R.string.tasks_job_active
        BackgroundJobActionOutcome.STOP_REQUESTED -> R.string.tasks_job_stop_requested
        BackgroundJobActionOutcome.TERMINAL_PENDING -> R.string.tasks_job_terminal_pending
        BackgroundJobActionOutcome.SETTLED -> R.string.tasks_job_settled
        BackgroundJobActionOutcome.BUSY -> R.string.tasks_job_executor_busy
        BackgroundJobActionOutcome.FAILED -> R.string.tasks_job_action_failed
        BackgroundJobActionOutcome.REVIEW_REQUIRED -> R.string.tasks_job_review_required
    }
