package com.helix.app.ui

import com.helix.app.R
import com.helix.app.chat.BackgroundTaskUi
import com.helix.app.chat.GoalStatusUi
import com.helix.app.chat.GoalSummaryUi
import com.helix.app.chat.GoalUsageUi
import com.helix.app.chat.PlanRowUi
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HXA-202 slice 1: the Tasks dashboard buckets are a pure projection of the persisted
 * Turn / Goal / approval facts. Covers the six display states, the reason distinctions
 * (paused / blocked / user-cancelled), the live-turn facts that a goal aggregates, and the
 * "needs review" rule that must come from unknown-side-effect facts only.
 */
class TasksDashboardProjectionTest {
    @Test
    fun runningTurnProjectsToRunningBucket() {
        val rows = rows(task(state = TurnState.RUNNING_TOOL))
        val row = turnRow(rows)
        assertEquals(TasksBucket.RUNNING, row.bucket)
        assertEquals(R.string.goal_state_running, row.statusRes)
    }

    @Test
    fun waitingApprovalTurnProjectsToNeedsYouWithApprovalLabel() {
        val rows = rows(task(state = TurnState.WAITING_APPROVAL))
        val row = turnRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.tasks_state_awaiting_approval, row.statusRes)
    }

    @Test
    fun cancellingTurnProjectsToCancellingBucketWithSettlementLabel() {
        val rows = rows(task(state = TurnState.CANCELLING))
        val row = turnRow(rows)
        assertEquals(TasksBucket.CANCELLING, row.bucket)
        assertEquals(R.string.tasks_state_cancelling, row.statusRes)
    }

    @Test
    fun cancellingTurnAfterUserPauseKeepsThePauseReason() {
        val rows = rows(task(state = TurnState.CANCELLING, pauseRequested = true))
        val row = turnRow(rows)
        assertEquals(TasksBucket.CANCELLING, row.bucket)
        assertEquals(R.string.background_task_pausing, row.statusRes)
    }

    @Test
    fun completedTurnProjectsToCompletedBucket() {
        val rows = rows(task(state = TurnState.COMPLETED))
        val row = turnRow(rows)
        assertEquals(TasksBucket.COMPLETED, row.bucket)
        assertEquals(R.string.goal_state_completed, row.statusRes)
    }

    @Test
    fun failedTurnProjectsToFailedBucket() {
        val rows = rows(task(state = TurnState.FAILED))
        val row = turnRow(rows)
        assertEquals(TasksBucket.FAILED, row.bucket)
        assertEquals(R.string.goal_state_failed, row.statusRes)
    }

    @Test
    fun userCancelledTurnProjectsToFailedBucketWithCancelReason() {
        val rows = rows(task(state = TurnState.CANCELLED))
        val row = turnRow(rows)
        assertEquals(TasksBucket.FAILED, row.bucket)
        assertEquals(R.string.goal_state_cancelled, row.statusRes)
    }

    @Test
    fun userPausedTurnIsSettledButStillNeedsYouNotRunningOrFailed() {
        val rows = rows(task(state = TurnState.CANCELLED, outcome = "USER_PAUSED"))
        val row = turnRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.goal_state_paused, row.statusRes)
    }

    @Test
    fun interruptedTurnProjectsToNeedsYouWithNeedsReviewLabel() {
        val rows = rows(task(state = TurnState.INTERRUPTED))
        val row = turnRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.tasks_state_needs_review, row.statusRes)
    }

    @Test
    fun budgetExhaustedTurnKeepsTheBlockedReasonLabel() {
        val rows = rows(task(state = TurnState.CANCELLED, outcome = "BUDGET_EXHAUSTED(wake duration)"))
        val row = turnRow(rows)
        assertEquals(R.string.tasks_need_blocker, row.statusRes)
    }

    @Test
    fun pausedGoalIsNeedsYouNotRunning() {
        val rows = rows(task(), goals = listOf(goal(state = "PAUSED")))
        val row = goalRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.goal_state_paused, row.statusRes)
    }

    @Test
    fun runningGoalWithHiddenCancellingTurnProjectsToCancellingBucket() {
        val rows =
            rows(
                task(state = TurnState.CANCELLING, goalId = "goal-1"),
                goals = listOf(goal(state = "RUNNING")),
            )
        assertEquals(1, rows.size)
        val row = goalRow(rows)
        assertEquals(TasksBucket.CANCELLING, row.bucket)
        assertEquals(R.string.tasks_state_cancelling, row.statusRes)
    }

    @Test
    fun runningGoalWithHiddenWaitingApprovalTurnSurfacesTheApprovalWait() {
        val rows =
            rows(
                task(state = TurnState.WAITING_APPROVAL, goalId = "goal-1"),
                goals = listOf(goal(state = "RUNNING")),
            )
        assertEquals(1, rows.size)
        val row = goalRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.tasks_state_awaiting_approval, row.statusRes)
    }

    @Test
    fun runningGoalWithUnresolvedCallsShowsNeedsReviewOnlyFromTheFact() {
        val rows = rows(task(), goals = listOf(goal(state = "RUNNING", hasUnresolvedCalls = true)))
        val row = goalRow(rows)
        assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
        assertEquals(R.string.tasks_state_needs_review, row.statusRes)
    }

    @Test
    fun failedGoalWithoutUnresolvedCallsKeepsTheFailedLabel() {
        val rows = rows(task(), goals = listOf(goal(state = "FAILED")))
        val row = goalRow(rows)
        assertEquals(TasksBucket.FAILED, row.bucket)
        assertEquals(R.string.goal_state_failed, row.statusRes)
    }

    @Test
    fun endedTurnNeverMarksTheWholeGoalCompleted() {
        // The goal's own persisted state is still RUNNING while its last turn is COMPLETED:
        // the turn row disappears (bound, deduplicated) but the goal stays in progress.
        val rows =
            rows(
                task(state = TurnState.COMPLETED, goalId = "goal-1"),
                goals = listOf(goal(state = "RUNNING")),
            )
        assertEquals(1, rows.size)
        val row = goalRow(rows)
        assertEquals(TasksBucket.RUNNING, row.bucket)
        assertEquals(R.string.goal_state_running, row.statusRes)
    }

    @Test
    fun completedGoalProjectsToCompletedBucket() {
        val rows = rows(task(), goals = listOf(goal(state = "COMPLETED")))
        val row = goalRow(rows)
        assertEquals(TasksBucket.COMPLETED, row.bucket)
        assertEquals(R.string.goal_state_completed, row.statusRes)
    }

    @Test
    fun readyAndDraftGoalsWaitForTheUser() {
        for (state in listOf("READY", "DRAFT")) {
            val rows = rows(task(), goals = listOf(goal(state = state)))
            val row = goalRow(rows)
            assertEquals(TasksBucket.NEEDS_YOU, row.bucket)
            assertEquals(R.string.goal_state_ready, row.statusRes)
        }
    }

    @Test
    fun inputRequiredAndBlockedGoalsKeepTheirReasonLabels() {
        val input = goalRow(rows(task(), goals = listOf(goal(state = "INPUT_REQUIRED"))))
        assertEquals(TasksBucket.NEEDS_YOU, input.bucket)
        assertEquals(R.string.tasks_need_input, input.statusRes)
        val blocked = goalRow(rows(task(), goals = listOf(goal(state = "BLOCKED"))))
        assertEquals(TasksBucket.NEEDS_YOU, blocked.bucket)
        assertEquals(R.string.tasks_need_blocker, blocked.statusRes)
    }

    @Test
    fun unboundTurnsInDifferentSessionsKeepSeparateRowsForTheSameTitle() {
        val rows =
            rows(
                task(id = "turn-a", sessionId = "session-1"),
                task(id = "turn-b", sessionId = "session-2"),
            )
        assertEquals(2, rows.size)
        assertEquals(setOf("turn-turn-a", "turn-turn-b"), rows.map { it.key }.toSet())
    }

    @Test
    fun onlyReadyPlansEnterTheQueue() {
        val rows =
            rows(
                task(),
                plans = listOf(plan(state = "READY"), plan(id = "plan-2", state = "APPROVED")),
            )
        assertEquals(
            2,
            rows.size,
        )
        val plans = rows.filterIsInstance<TasksRow.Plan>()
        assertEquals(1, plans.size)
        assertEquals("plan-plan-1", plans.single().key)
        assertEquals(TasksBucket.NEEDS_YOU, plans.single().bucket)
        assertEquals(R.string.tasks_plan_ready, plans.single().statusRes)
    }

    @Test
    fun taskStateLabelCoversCancellingWithoutChangingDialogApprovalLabel() {
        assertEquals(R.string.tasks_state_cancelling, taskStateLabel(TurnState.CANCELLING))
        assertEquals(R.string.goal_state_input, taskStateLabel(TurnState.WAITING_APPROVAL))
        assertEquals(R.string.goal_state_running, taskStateLabel(TurnState.WAITING_MODEL))
    }

    private fun rows(
        vararg tasks: BackgroundTaskUi,
        goals: List<GoalSummaryUi> = emptyList(),
        plans: List<PlanRowUi> = emptyList(),
    ): List<TasksRow> = tasksDashboardRows(tasks.toList(), goals, plans)

    private fun turnRow(rows: List<TasksRow>): TasksRow.Turn = rows.filterIsInstance<TasksRow.Turn>().single()

    private fun goalRow(rows: List<TasksRow>): TasksRow.Goal = rows.filterIsInstance<TasksRow.Goal>().single()

    private fun task(
        id: String = "turn-1",
        sessionId: String = "session-1",
        state: TurnState = TurnState.RUNNING_TOOL,
        goalId: String? = null,
        pauseRequested: Boolean = false,
        outcome: String? = null,
    ): BackgroundTaskUi =
        BackgroundTaskUi(
            id = id,
            sessionId = sessionId,
            title = "Same title",
            state = state,
            goalId = goalId,
            collected = false,
            pauseRequested = pauseRequested,
            outcome = outcome,
        )

    private fun goal(
        id: String = "goal-1",
        state: String = "RUNNING",
        hasUnresolvedCalls: Boolean = false,
    ): GoalSummaryUi =
        GoalSummaryUi(
            id = id,
            objective = "Objective",
            status = GoalStatusUi(state = state, outcome = null),
            criteria = emptyList(),
            budgets =
                GoalBudgets(
                    maxModelCalls = 1,
                    maxToolCalls = 1,
                    maxTotalTokens = 0,
                    maxDurationMillis = 0,
                    maxWakeDurationMillis = 0,
                    maxRetries = 0,
                ),
            usage = GoalUsageUi(0, 0, 0, 0),
            canContinue = false,
            hasUnresolvedCalls = hasUnresolvedCalls,
            canEditBudgets = false,
        )

    private fun plan(
        id: String = "plan-1",
        state: String = "READY",
    ): PlanRowUi = PlanRowUi(id = id, objective = "Plan objective", version = 1, state = state)
}
