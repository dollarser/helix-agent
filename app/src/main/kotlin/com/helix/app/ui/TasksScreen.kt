package com.helix.app.ui

import androidx.annotation.StringRes
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
import com.helix.app.chat.BackgroundTaskUi
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import com.helix.app.chat.PlanRowUi
import com.helix.core.model.TurnState

/**
 * The cross-session task dashboard (P0-B, doc section 13): background turns, persistent
 * goals and review-required plans bucketed into running / needs-you / completed / failed.
 * A turn bound to a goal renders as the goal row (one row per unit of work); the "needs
 * you" bucket aggregates the doc's approval / input / blocker / plan-review waits.
 */
@Composable
@Suppress("FunctionName")
internal fun TasksScreen(
    service: ChatService,
    onOpenSession: (String) -> Unit,
) {
    val tasks by service.backgroundTasks.collectAsStateWithLifecycle()
    var goals by remember { mutableStateOf<List<GoalSummaryUi>>(emptyList()) }
    var plans by remember { mutableStateOf<List<PlanRowUi>>(emptyList()) }
    var revision by remember { mutableStateOf(0L) }
    var selectedPlan by remember { mutableStateOf<String?>(null) }
    var resultTurn by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        goals = service.goalSummariesAll()
        plans = service.planRows()
    }

    if (resultTurn != null) {
        TaskResultDialog(service, requireNotNull(resultTurn)) { resultTurn = null }
        return
    }
    if (selectedPlan != null) {
        PlanReviewDialog(service, requireNotNull(selectedPlan)) {
            selectedPlan = null
            revision += 1
        }
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

/**
 * One row per unit of work: a turn bound to a visible goal is deduplicated into the goal
 * row; only READY (review-required) plans enter the queue.
 */
private fun tasksDashboardRows(
    tasks: List<BackgroundTaskUi>,
    goals: List<GoalSummaryUi>,
    plans: List<PlanRowUi>,
): List<TasksRow> {
    val goalById: Map<String, GoalSummaryUi> = goals.associateBy { it.id }
    return buildList {
        tasks
            .filter { it.goalId == null || !goalById.containsKey(it.goalId) }
            .forEach { add(TasksRow.Turn(it)) }
        goals.forEach { add(TasksRow.Goal(it)) }
        plans.filter { it.state == "READY" }.forEach { add(TasksRow.Plan(it)) }
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

private enum class TasksBucket(
    @StringRes val titleRes: Int,
) {
    RUNNING(R.string.tasks_bucket_running),
    NEEDS_YOU(R.string.tasks_bucket_needs_you),
    COMPLETED(R.string.tasks_bucket_completed),
    FAILED(R.string.tasks_bucket_failed),
}

private sealed interface TasksRow {
    val key: String
    val testTag: String
    val bucket: TasksBucket
    val title: String
    val kindRes: Int
    val statusRes: Int

    data class Turn(
        val task: BackgroundTaskUi,
    ) : TasksRow {
        override val key: String get() = "turn-${task.id}"
        override val testTag: String get() = "tasks-turn-${task.id}"
        override val title: String get() = task.title
        override val kindRes: Int get() = R.string.tasks_kind_turn
        override val bucket: TasksBucket
            get() =
                when (task.state) {
                    TurnState.COMPLETED -> TasksBucket.COMPLETED
                    TurnState.FAILED, TurnState.CANCELLED -> TasksBucket.FAILED
                    TurnState.WAITING_APPROVAL -> TasksBucket.NEEDS_YOU
                    else -> TasksBucket.RUNNING
                }

        override val statusRes: Int
            get() =
                when {
                    task.outcome == "USER_PAUSED" -> R.string.goal_state_paused

                    task.outcome?.startsWith("BLOCKED(") == true ||
                        task.outcome?.startsWith("BUDGET_EXHAUSTED(") == true -> R.string.goal_state_blocked

                    else -> taskStateLabel(task.state)
                }
    }

    data class Goal(
        val goal: GoalSummaryUi,
    ) : TasksRow {
        override val key: String get() = "goal-${goal.id}"
        override val testTag: String get() = "tasks-goal-${goal.id}"
        override val title: String get() = goal.objective
        override val kindRes: Int get() = R.string.tasks_kind_goal
        override val bucket: TasksBucket
            get() =
                when (goal.status.state) {
                    "COMPLETED" -> TasksBucket.COMPLETED
                    "FAILED", "CANCELLED" -> TasksBucket.FAILED
                    "INPUT_REQUIRED", "BLOCKED", "READY", "DRAFT" -> TasksBucket.NEEDS_YOU
                    else -> TasksBucket.RUNNING
                }

        override val statusRes: Int get() = tasksGoalStateLabel(goal.status.state)
    }

    data class Plan(
        val plan: PlanRowUi,
    ) : TasksRow {
        override val key: String get() = "plan-${plan.id}"
        override val testTag: String get() = "tasks-plan-${plan.id}"
        override val title: String get() = plan.objective
        override val kindRes: Int get() = R.string.tasks_kind_plan
        override val bucket: TasksBucket get() = TasksBucket.NEEDS_YOU
        override val statusRes: Int get() = R.string.tasks_plan_ready
    }
}

private fun tasksGoalStateLabel(state: String): Int =
    when (state) {
        "RUNNING" -> R.string.goal_state_running
        "PAUSED" -> R.string.goal_state_paused
        "INPUT_REQUIRED" -> R.string.tasks_need_input
        "BLOCKED" -> R.string.tasks_need_blocker
        "COMPLETED" -> R.string.goal_state_completed
        "FAILED" -> R.string.goal_state_failed
        "CANCELLED" -> R.string.goal_state_cancelled
        else -> R.string.goal_state_ready
    }
