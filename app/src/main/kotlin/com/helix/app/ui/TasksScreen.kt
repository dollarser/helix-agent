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
import com.helix.app.files.FileManagerService

/**
 * The cross-session task dashboard (P0-B, doc section 13): background turns, persistent
 * goals and review-required plans bucketed into running / needs-you / cancelling /
 * completed / failed. A turn bound to a goal renders as the goal row (one row per unit
 * of work); the row's live-turn facts keep the current turn's approval wait, cancellation
 * and needs-review visible while the goal aggregates it. The bucketing itself is the pure
 * projection [tasksDashboardRows] in TasksDashboard.kt over the persisted facts.
 *
 * Every row is located by its stable ID (turn / goal / plan) — never by title, so two
 * same-titled tasks in different sessions stay separate rows and each opens ITS session.
 * The Goal entry both selects its owning session and actually switches to the chat page
 * (HXA-202 slice 2); a turn's artifacts are read by real ownership through
 * [TaskArtifactsDialog], never the truncated global window. Opening, refreshing and
 * observing this screen starts or continues nothing.
 *
 * All three feeds are live StateFlows the service refreshes after every write (turn-state
 * changes, goal and plan mutations), so the screen observes the persistent facts directly —
 * no re-query on entry or after local actions.
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun TasksScreen(
    service: ChatService,
    fileManager: FileManagerService,
    onOpenSession: (String) -> Unit,
    onOpenCommandDetail: (String, String) -> Unit = { _, _ -> },
) {
    val tasks by service.backgroundTasks.collectAsStateWithLifecycle()
    val goals by service.goalDashboard.collectAsStateWithLifecycle()
    val plans by service.planDashboard.collectAsStateWithLifecycle()
    var selectedPlan by remember { mutableStateOf<String?>(null) }
    var resultTurn by remember { mutableStateOf<String?>(null) }
    var commandTurn by remember { mutableStateOf<String?>(null) }
    var artifactsQuery by remember { mutableStateOf<TaskArtifactsQuery?>(null) }

    // Entry refresh: facts written while the app was closed (or by another process) become
    // visible on open; afterwards the shared flows stay live.
    LaunchedEffect(Unit) {
        service.refreshBackgroundTasksNow()
        service.refreshTaskDashboardsNow()
    }

    // One modal dialog at a time, rendered in place of the list; the shared flows keep the
    // row data live underneath.
    val openDialog =
        listOfNotNull(resultTurn, selectedPlan, commandTurn, artifactsQuery).isNotEmpty()
    if (openDialog) {
        when {
            resultTurn != null -> {
                TaskResultDialog(service, requireNotNull(resultTurn)) { resultTurn = null }
            }

            selectedPlan != null -> {
                // The review dialog's decisions go through the service, which refreshes the
                // plan feed itself — no screen-side revision bump needed.
                PlanReviewDialog(service, requireNotNull(selectedPlan)) { selectedPlan = null }
            }

            commandTurn != null -> {
                // HXA-194: the turn's commands list; opening a call navigates to the
                // details page (the dialog dismisses, the list is re-read per open).
                TurnCommandListDialog(
                    service,
                    requireNotNull(commandTurn),
                    onOpenCommandDetail = { turnId, callId ->
                        commandTurn = null
                        onOpenCommandDetail(turnId, callId)
                    },
                ) { commandTurn = null }
            }

            else -> {
                val query = requireNotNull(artifactsQuery)
                TaskArtifactsDialog(
                    service,
                    fileManager,
                    query.turnId,
                    query.goalId,
                    onOpenSession,
                ) { artifactsQuery = null }
            }
        }
        return
    }

    TasksRowList(
        tasksDashboardRows(tasks, goals, plans),
        service,
        onOpenSession,
        { resultTurn = it },
        { selectedPlan = it },
        { commandTurn = it },
        { artifactsQuery = it },
    )
}

/** The dashboard list itself: the buckets with their rows, or the honest empty state. */
@Composable
@Suppress("FunctionName", "LongParameterList")
private fun TasksRowList(
    rows: List<TasksRow>,
    service: ChatService,
    onOpenSession: (String) -> Unit,
    onShowResult: (String) -> Unit,
    onReviewPlan: (String) -> Unit,
    onShowCommands: (String) -> Unit,
    onShowArtifacts: (TaskArtifactsQuery) -> Unit,
) {
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
                        onShowResult,
                        onReviewPlan,
                        onShowCommands,
                        onShowArtifacts,
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
    onShowCommands: (String) -> Unit,
    onShowArtifacts: (TaskArtifactsQuery) -> Unit,
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
                    TurnRowActions(
                        row,
                        service,
                        onOpenSession,
                        onShowResult,
                        onShowCommands,
                        onShowArtifacts,
                    )
                }

                is TasksRow.Goal -> {
                    // The Goal entry selects its owning session AND switches to the chat
                    // page (HXA-202 slice 2): the reminder stays an observation, never a
                    // Continued, and the navigation goes to the session the goal owns.
                    TextButton(
                        {
                            service.openGoalReminder(row.goal.id)
                            row.goal.sessionId?.let(onOpenSession)
                        },
                        Modifier.testTag("tasks-goal-open-${row.goal.id}"),
                    ) { Text(stringResource(R.string.background_task_open)) }
                    TextButton(
                        { onShowArtifacts(TaskArtifactsQuery(turnId = null, goalId = row.goal.id)) },
                        Modifier.testTag("tasks-goal-artifacts-${row.goal.id}"),
                    ) { Text(stringResource(R.string.tasks_artifacts)) }
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

/** Where the task-artifact read points: a turn's own rows, or a goal's bound-turn union. */
private data class TaskArtifactsQuery(
    val turnId: String?,
    val goalId: String?,
)

/**
 * The turn row's actions: open the owning session always; a running turn offers cancel and
 * the command list (a running command's details show status only — the output is viewable
 * after the command ends); a settled turn offers result, commands and artifacts (HXA-194:
 * the command list entry is the task page's path into the command details page).
 */
@Composable
@Suppress("FunctionName")
private fun TurnRowActions(
    row: TasksRow.Turn,
    service: ChatService,
    onOpenSession: (String) -> Unit,
    onShowResult: (String) -> Unit,
    onShowCommands: (String) -> Unit,
    onShowArtifacts: (TaskArtifactsQuery) -> Unit,
) {
    TextButton(
        { onOpenSession(row.task.sessionId) },
        Modifier.testTag("tasks-turn-open-${row.task.id}"),
    ) { Text(stringResource(R.string.background_task_open)) }
    if (row.task.running) {
        TextButton(
            { service.stopTask(row.task.id) },
            Modifier.testTag("tasks-turn-cancel-${row.task.id}"),
        ) { Text(stringResource(R.string.background_task_cancel)) }
        TextButton(
            { onShowCommands(row.task.id) },
            Modifier.testTag("tasks-turn-command-${row.task.id}"),
        ) { Text(stringResource(R.string.tasks_turn_command)) }
    } else {
        TextButton(
            { onShowResult(row.task.id) },
            Modifier.testTag("tasks-turn-result-${row.task.id}"),
        ) { Text(stringResource(R.string.background_task_result)) }
        TextButton(
            { onShowCommands(row.task.id) },
            Modifier.testTag("tasks-turn-command-${row.task.id}"),
        ) { Text(stringResource(R.string.tasks_turn_command)) }
        TextButton(
            { onShowArtifacts(TaskArtifactsQuery(turnId = row.task.id, goalId = null)) },
            Modifier.testTag("tasks-turn-artifacts-${row.task.id}"),
        ) { Text(stringResource(R.string.tasks_artifacts)) }
    }
}
