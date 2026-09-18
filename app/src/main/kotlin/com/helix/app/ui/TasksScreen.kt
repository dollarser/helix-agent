package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
 * HXA-203: the dedicated "return to the producing task" route (the same pattern as the
 * command-details route): its own backstack entry, so system back returns to the page the
 * artifact row was opened from, and the dashboard lands on that turn's result dialog.
 */
internal const val TASKS_TURN_ROUTE = "tasks-turn/{turnId}"

internal fun tasksTurnRoute(turnId: String): String = "tasks-turn/$turnId"

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
 *
 * [initialTurnId] (HXA-203) opens that turn's result dialog on entry — the landing of the
 * [TASKS_TURN_ROUTE] route; [onBack] shows the back bar (system back also pops the route).
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun TasksScreen(
    service: ChatService,
    fileManager: FileManagerService,
    onOpenSession: (String) -> Unit,
    onOpenCommandDetail: (String, String) -> Unit = { _, _ -> },
    initialTurnId: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val selectedPlanState = remember { mutableStateOf<String?>(null) }
    val resultTurnState = remember { mutableStateOf<String?>(null) }
    val commandTurnState = remember { mutableStateOf<String?>(null) }
    val artifactsQueryState = remember { mutableStateOf<TaskArtifactsQuery?>(null) }
    var selectedPlan by selectedPlanState
    var resultTurn by resultTurnState
    var commandTurn by commandTurnState
    var artifactsQuery by artifactsQueryState

    val tasks by service.backgroundTasks.collectAsStateWithLifecycle()
    val jobs by service.backgroundJobs.collectAsStateWithLifecycle()
    val goals by service.goalDashboard.collectAsStateWithLifecycle()
    val plans by service.planDashboard.collectAsStateWithLifecycle()

    // Entry refresh: facts written while the app was closed (or by another process) become
    // visible on open; afterwards the shared flows stay live.
    LaunchedEffect(Unit) {
        service.refreshBackgroundTasksNow()
        service.refreshTaskDashboardsNow()
    }

    // HXA-203: arriving from an artifact's "view task" action lands on that turn's result
    // dialog (its persisted result or the honest missing state); the route's back pops back
    // to the page the row was opened from.
    LaunchedEffect(initialTurnId) {
        initialTurnId?.let { resultTurn = it }
    }

    // One modal dialog at a time, rendered in place of the list; the shared flows keep the
    // row data live underneath.
    val openDialog =
        listOfNotNull(resultTurn, selectedPlan, commandTurn, artifactsQuery).isNotEmpty()
    if (openDialog) {
        TaskModals(
            service,
            fileManager,
            onOpenSession,
            onOpenCommandDetail,
            resultTurnState,
            selectedPlanState,
            commandTurnState,
            artifactsQueryState,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TasksTurnBackBar(onBack)
        Box(Modifier.fillMaxSize()) {
            TasksRowList(
                tasksDashboardRows(tasks, goals, plans, jobs),
                service,
                onOpenSession,
                { resultTurn = it },
                { selectedPlan = it },
                { commandTurn = it },
                { artifactsQuery = it },
                onOpenCommandDetail,
            )
        }
    }
}

/**
 * The dashboard's modal dialogs (one at a time, rendered in place of the list): the turn
 * result, the plan review, the turn command list and the task artifacts. The shared flows
 * keep the row data live underneath.
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
private fun TaskModals(
    service: ChatService,
    fileManager: FileManagerService,
    onOpenSession: (String) -> Unit,
    onOpenCommandDetail: (String, String) -> Unit,
    resultTurn: MutableState<String?>,
    selectedPlan: MutableState<String?>,
    commandTurn: MutableState<String?>,
    artifactsQuery: MutableState<TaskArtifactsQuery?>,
) {
    when {
        resultTurn.value != null -> {
            TaskResultDialog(service, requireNotNull(resultTurn.value)) { resultTurn.value = null }
        }

        selectedPlan.value != null -> {
            // The review dialog's decisions go through the service, which refreshes the
            // plan feed itself — no screen-side revision bump needed.
            PlanReviewDialog(service, requireNotNull(selectedPlan.value)) { selectedPlan.value = null }
        }

        commandTurn.value != null -> {
            // HXA-194: the turn's commands list; opening a call navigates to the
            // details page (the dialog dismisses, the list is re-read per open).
            TurnCommandListDialog(
                service,
                requireNotNull(commandTurn.value),
                onOpenCommandDetail = { turnId, callId ->
                    commandTurn.value = null
                    onOpenCommandDetail(turnId, callId)
                },
            ) { commandTurn.value = null }
        }

        else -> {
            val query = requireNotNull(artifactsQuery.value)
            TaskArtifactsDialog(
                service,
                fileManager,
                query.turnId,
                query.goalId,
                onOpenSession,
            ) { artifactsQuery.value = null }
        }
    }
}

/** The [TASKS_TURN_ROUTE] landing's back bar; system back pops the route the same way. */
@Composable
@Suppress("FunctionName")
private fun TasksTurnBackBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("tasks-turn-back-bar"),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("tasks-turn-back")) {
            Text(stringResource(R.string.command_detail_back))
        }
    }
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
    onOpenCommandDetail: (String, String) -> Unit,
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
                        onOpenCommandDetail,
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
    onOpenCommandDetail: (String, String) -> Unit,
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
        if (row is BackgroundJobRow) {
            JobRowControls(row, service)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row) {
                is BackgroundJobRow -> {
                    TextButton(
                        { onOpenSession(row.job.sessionId) },
                        Modifier.testTag("tasks-job-open-${row.job.callId}"),
                    ) { Text(stringResource(R.string.background_task_open)) }
                    TextButton(
                        { onOpenCommandDetail(row.job.turnId, row.job.callId) },
                        Modifier.testTag("tasks-job-detail-${row.job.callId}"),
                    ) { Text(stringResource(R.string.chat_tool_command_detail)) }
                }

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

@Composable
@Suppress("FunctionName")
private fun JobRowControls(
    row: BackgroundJobRow,
    service: ChatService,
) {
    if (row.job.settlementPending) {
        Text(stringResource(R.string.tasks_job_pending), Modifier.testTag("tasks-job-pending-${row.job.callId}"))
    }
    val action by service.backgroundJobAction.collectAsStateWithLifecycle()
    BackgroundJobControls(row.job, action, service::performBackgroundJobAction)
}

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
