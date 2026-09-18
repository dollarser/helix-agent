package com.helix.app.ui

import androidx.annotation.StringRes
import com.helix.app.R
import com.helix.app.chat.BackgroundTaskUi
import com.helix.app.chat.GoalSummaryUi
import com.helix.app.chat.PlanRowUi
import com.helix.core.model.TurnState

/**
 * Display-state projection for the cross-session Tasks dashboard (HXA-202).
 *
 * The six display states — running / waiting for the user / cancelling / settled /
 * failed-or-cancelled / needs review — are pure functions of the persisted Turn, Goal and
 * approval facts in [BackgroundTaskUi] and [GoalSummaryUi]. There is no second persistent
 * task state machine here: opening, refreshing or recreating the page recomputes the same
 * projection and never creates or mutates a turn, goal run or approval proof.
 *
 * Reason distinctions survive the bucketing: a paused turn, a budget-blocked goal and a
 * user-cancellation keep their own status labels; "needs review" is only ever derived from
 * the unknown-side-effect facts (interrupted tool calls, process death), not from any
 * failure in general.
 */
internal enum class TasksBucket(
    @StringRes val titleRes: Int,
) {
    RUNNING(R.string.tasks_bucket_running),
    NEEDS_YOU(R.string.tasks_bucket_needs_you),
    CANCELLING(R.string.tasks_bucket_cancelling),
    COMPLETED(R.string.tasks_bucket_completed),
    FAILED(R.string.tasks_bucket_failed),
}

/** Settled outcome marking a user pause; the turn was cancelled on purpose, not a failure. */
internal const val USER_PAUSED_OUTCOME = "USER_PAUSED"

/** Non-terminal goal states that wait on the user, never on the machine. */
internal val NEEDS_YOU_GOAL_STATES: Set<String> =
    setOf("INPUT_REQUIRED", "BLOCKED", "PAUSED", "READY", "DRAFT")

/** Terminal goal states; only these may place a goal row in a settled bucket. */
internal val TERMINAL_GOAL_STATES: Set<String> =
    setOf("COMPLETED", "FAILED", "CANCELLED")

internal sealed interface TasksRow {
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
                when {
                    task.state == TurnState.CANCELLING -> {
                        TasksBucket.CANCELLING
                    }

                    task.state == TurnState.COMPLETED -> {
                        TasksBucket.COMPLETED
                    }

                    task.state == TurnState.FAILED -> {
                        TasksBucket.FAILED
                    }

                    task.state == TurnState.CANCELLED -> {
                        if (task.outcome == USER_PAUSED_OUTCOME) {
                            TasksBucket.NEEDS_YOU
                        } else {
                            TasksBucket.FAILED
                        }
                    }

                    // Approval waits on the user; a process-death interruption hides unknown
                    // side effects until reviewed — both are "needs you", not "running".
                    task.state == TurnState.WAITING_APPROVAL ||
                        task.state == TurnState.INTERRUPTED -> {
                        TasksBucket.NEEDS_YOU
                    }

                    else -> {
                        TasksBucket.RUNNING
                    }
                }

        override val statusRes: Int
            get() =
                when {
                    task.outcome == USER_PAUSED_OUTCOME -> {
                        R.string.goal_state_paused
                    }

                    task.state == TurnState.CANCELLING -> {
                        if (task.pauseRequested) {
                            R.string.background_task_pausing
                        } else {
                            R.string.tasks_state_cancelling
                        }
                    }

                    task.state == TurnState.INTERRUPTED -> {
                        R.string.tasks_state_needs_review
                    }

                    task.state == TurnState.WAITING_APPROVAL -> {
                        R.string.tasks_state_awaiting_approval
                    }

                    task.outcome?.startsWith("BLOCKED(") == true ||
                        task.outcome?.startsWith("BUDGET_EXHAUSTED(") == true -> {
                        R.string.tasks_need_blocker
                    }

                    else -> {
                        taskStateLabel(task.state)
                    }
                }
    }

    /**
     * [activeTurn] is the goal's bound live turn, carried in because a goal hides its bound
     * turns from the row list — the row must still surface the current turn's approval wait,
     * cancellation and settlement. A turn ending never settles the goal: the bucket only
     * follows the goal's own persisted state plus these live-turn facts.
     */
    data class Goal(
        val goal: GoalSummaryUi,
        val activeTurn: BackgroundTaskUi?,
    ) : TasksRow {
        override val key: String get() = "goal-${goal.id}"
        override val testTag: String get() = "tasks-goal-${goal.id}"
        override val title: String get() = goal.objective
        override val kindRes: Int get() = R.string.tasks_kind_goal

        override val bucket: TasksBucket
            get() =
                when {
                    goal.status.state in TERMINAL_GOAL_STATES -> {
                        if (goal.status.state == "COMPLETED") {
                            TasksBucket.COMPLETED
                        } else {
                            TasksBucket.FAILED
                        }
                    }

                    goal.status.state in NEEDS_YOU_GOAL_STATES -> {
                        TasksBucket.NEEDS_YOU
                    }

                    activeTurn?.state == TurnState.CANCELLING -> {
                        TasksBucket.CANCELLING
                    }

                    activeTurn?.state == TurnState.WAITING_APPROVAL -> {
                        TasksBucket.NEEDS_YOU
                    }

                    goal.hasUnresolvedCalls -> {
                        TasksBucket.NEEDS_YOU
                    }

                    else -> {
                        TasksBucket.RUNNING
                    }
                }

        override val statusRes: Int
            get() =
                when {
                    activeTurn?.state == TurnState.CANCELLING -> {
                        if (activeTurn.pauseRequested) {
                            R.string.background_task_pausing
                        } else {
                            R.string.tasks_state_cancelling
                        }
                    }

                    activeTurn?.state == TurnState.WAITING_APPROVAL -> {
                        R.string.tasks_state_awaiting_approval
                    }

                    goal.status.state !in TERMINAL_GOAL_STATES && goal.hasUnresolvedCalls -> {
                        R.string.tasks_state_needs_review
                    }

                    else -> {
                        tasksGoalStateLabel(goal.status.state)
                    }
                }
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

/**
 * One row per unit of work. A turn bound to a visible goal is deduplicated into the goal
 * row but kept as that row's live-turn fact; only READY (review-required) plans enter the
 * queue.
 */
internal fun tasksDashboardRows(
    tasks: List<BackgroundTaskUi>,
    goals: List<GoalSummaryUi>,
    plans: List<PlanRowUi>,
    jobs: List<com.helix.app.proot.BackgroundJobUi> = emptyList(),
): List<TasksRow> {
    val goalById: Map<String, GoalSummaryUi> = goals.associateBy { it.id }
    val liveTurnByGoal =
        tasks
            .filter { it.running }
            .groupBy { it.goalId }
            .mapValues { entry -> entry.value.first() }
    return buildList {
        tasks
            .filter { it.goalId == null || !goalById.containsKey(it.goalId) }
            .forEach { add(TasksRow.Turn(it)) }
        goals.forEach { goal -> add(TasksRow.Goal(goal, liveTurnByGoal[goal.id])) }
        plans.filter { it.state == "READY" }.forEach { add(TasksRow.Plan(it)) }
        jobs.distinctBy { it.callId }.forEach { add(BackgroundJobRow(it)) }
    }
}

internal fun taskStateLabel(state: TurnState): Int =
    when (state) {
        TurnState.COMPLETED -> R.string.goal_state_completed
        TurnState.FAILED -> R.string.goal_state_failed
        TurnState.CANCELLED -> R.string.goal_state_cancelled
        TurnState.INTERRUPTED -> R.string.goal_pause_interrupted
        TurnState.WAITING_APPROVAL -> R.string.goal_state_input
        TurnState.CANCELLING -> R.string.tasks_state_cancelling
        else -> R.string.goal_state_running
    }

internal fun tasksGoalStateLabel(state: String): Int =
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
