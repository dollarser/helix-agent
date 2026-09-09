package com.helix.app.chat

import com.helix.app.runcontrol.RunControlStore
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit Goal management; starting a Turn remains subject to the facade's send admission. */
@Suppress("LongParameterList")
internal class ChatGoalActions(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val requestAssembler: ChatRequestAssembler,
    private val runControlStore: RunControlStore,
    private val openSessionId: () -> String?,
    private val goalReminderSync: (String) -> Unit,
) {
    /** User-action service entry; UI integration follows the complete execution-budget wiring. */
    suspend fun createGoal(
        objective: String,
        criteria: List<String>,
        budgets: com.helix.core.model.GoalBudgets,
    ): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            GoalRunCoordinator(storage, clock, idGenerator).create(objective, criteria, budgets)
        }

    suspend fun goalSummaries(): List<GoalSummaryUi> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            openSessionId()?.let { GoalSummaryQuery(storage).forSession(it) } ?: emptyList()
        }

    suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val checkpoint =
                delayMillis?.let {
                    require(it >= 0)
                    com.helix.core.agent
                        .Checkpoint(Math.addExact(clock.now().toEpochMilli(), it))
                }
            val changed = GoalRunCoordinator(storage, clock, idGenerator).setCheckpoint(goalId, checkpoint)
            if (changed) goalReminderSync(goalId)
            changed
        }

    suspend fun recheckGoalBlocker(goalId: String): Boolean =
        withContext(Dispatchers.IO) {
            val session = openSessionId() ?: return@withContext false
            val goal = storage.goals.resolve(goalId)
            val outcome =
                storage.goalRuns
                    .listByGoal(goalId)
                    .lastOrNull()
                    ?.outcome
            val fits =
                outcome != "BLOCKED(CONTEXT_WINDOW_LIMIT)" ||
                    requestAssembler.contextFits(session, runControlStore.flow.value, goal.objective)
            GoalBlockerResolution(storage, clock, idGenerator).resolve(goalId, session, fits)
        }

    suspend fun updateGoalBudgets(
        goalId: String,
        budgets: com.helix.core.model.GoalBudgets,
    ): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            GoalRunCoordinator(storage, clock, idGenerator).updateBudgets(goalId, budgets)
        }
}
