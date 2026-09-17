package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService

/**
 * The cross-session task dashboard (P0-B, doc section 13): background turns, persistent
 * goals and review-required plans bucketed into running / needs-you / cancelling /
 * completed / failed. A turn bound to a goal renders as the goal row (one row per unit
 * of work); the row's live-turn facts keep the current turn's approval wait, cancellation
 * and needs-review visible while the goal aggregates it. The bucketing itself is the pure
 * projection [tasksDashboardRows] in TasksDashboard.kt over the persisted facts.
 *
 * All three feeds are live StateFlows the service refreshes after every write (turn-state
 * changes, goal and plan mutations), so the screen observes the persistent facts directly —
 * no re-query on entry or after local actions.
 */
@Composable
@Suppress("FunctionName")
internal fun TasksScreen(
    service: ChatService,
    onOpenSession: (String) -> Unit,
) {
    val tasks by service.backgroundTasks.collectAsStateWithLifecycle()
    val goals by service.goalDashboard.collectAsStateWithLifecycle()
    val plans by service.planDashboard.collectAsStateWithLifecycle()
    var selectedPlan by remember { mutableStateOf<String?>(null) }
    var resultTurn by remember { mutableStateOf<String?>(null) }

    // Entry refresh: facts written while the app was closed (or by another process) become
    // visible on open; afterwards the shared flows stay live.
    LaunchedEffect(Unit) {
        service.refreshBackgroundTasksNow()
        service.refreshTaskDashboardsNow()
    }

    if (resultTurn != null) {
        TaskResultDialog(service, requireNotNull(resultTurn)) { resultTurn = null }
        return
    }
    if (selectedPlan != null) {
        // The review dialog's decisions go through the service, which refreshes the plan
        // feed itself — no screen-side revision bump needed.
        PlanReviewDialog(service, requireNotNull(selectedPlan)) { selectedPlan = null }
        return
    }

    val rows = tasksDashboardRows(tasks, goals, plans)

    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().testTag("screen-tasks")) {
            Text(stringResource(R.string.tasks_empty), Modifier.padding(24.dp).testTag("tasks-empty"))
        }
    } else {
        LazyColumn(
            Modifier.testTag("screen-tasks"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TasksBucket.values().forEach { bucket ->
                val bucketRows = rows.filter { it.bucket == bucket }
                if (bucketRows.isEmpty()) return@forEach
                item(key = "bucket-${bucket.name}") {
                    Text(
                        stringResource(bucket.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("tasks-bucket-${bucket.name.lowercase()}"),
                    )
                }
                items(bucketRows, key = TasksRow::key) { row ->
                    TasksRowView(
                        row,
                        service,
                        onOpenSession,
                        { resultTurn = it },
                        { selectedPlan = it },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun TasksRowView(
    row: TasksRow,
    service: ChatService,
    onOpenSession: (String) -> Unit,
    onShowResult: (String) -> Unit,
    onReviewPlan: (String) -> Unit,
) {
    Column(Modifier.testTag(row.testTag)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(row.title)
            Text(stringResource(row.kindRes), style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(row.statusRes), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row) {
                is TasksRow.Turn -> {
                    TextButton(
                        { onOpenSession(row.task.sessionId) },
                        Modifier.testTag("tasks-turn-open-${row.task.id}"),
                    ) { Text(stringResource(R.string.background_task_open)) }
                    if (row.task.running) {
                        TextButton(
                            { service.stopTask(row.task.id) },
                            Modifier.testTag("tasks-turn-cancel-${row.task.id}"),
                        ) { Text(stringResource(R.string.background_task_cancel)) }
                    } else {
                        TextButton(
                            { onShowResult(row.task.id) },
                            Modifier.testTag("tasks-turn-result-${row.task.id}"),
                        ) { Text(stringResource(R.string.background_task_result)) }
                    }
                }

                is TasksRow.Goal -> {
                    TextButton(
                        { service.openGoalReminder(row.goal.id) },
                        Modifier.testTag("tasks-goal-open-${row.goal.id}"),
                    ) { Text(stringResource(R.string.background_task_open)) }
                }

                is TasksRow.Plan -> {
                    TextButton(
                        { onReviewPlan(row.plan.id) },
                        Modifier.testTag("tasks-plan-review-${row.plan.id}"),
                    ) { Text(stringResource(R.string.plan_review)) }
                }
            }
        }
    }
}
