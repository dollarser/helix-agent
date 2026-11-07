package com.helix.app.infrastructure.conversation

import com.helix.app.application.agent.GoalBlockerResolution
import com.helix.app.application.agent.GoalRunCoordinator
import com.helix.app.chat.GoalSummaryQuery
import com.helix.app.infrastructure.agent.RoomGoalOwnership
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.goals.ConversationGoals
import com.helix.feature.conversation.presentation.GoalSummaryUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit Goal management; starting a Turn remains subject to the facade's send admission. */
@Suppress("LongParameterList")
internal class RoomConversationGoals(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val contextFits: suspend (sessionId: String, objective: String) -> Boolean,
    private val turnGate: Any,
    private val goalReminderSync: (String) -> Unit,
) : ConversationGoals {
    /** User-action service entry; UI integration follows the complete execution-budget wiring. */
    override suspend fun createGoal(
        objective: String,
        criteria: List<String>,
        budgets: com.helix.core.model.GoalBudgets,
    ): String =
        withContext(Dispatchers.IO) {
            GoalRunCoordinator(storage, clock, idGenerator).create(objective, criteria, budgets)
        }

    override suspend fun goalSummaries(sessionId: String?): List<GoalSummaryUi> =
        withContext(Dispatchers.IO) {
            sessionId?.let { GoalSummaryQuery(storage).forSession(it) } ?: emptyList()
        }

    /** Cross-session goal list for the Tasks dashboard (doc section 13). */
    override suspend fun goalSummariesAll(): List<GoalSummaryUi> =
        withContext(Dispatchers.IO) { GoalSummaryQuery(storage).forAll() }

    override suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ): Boolean =
        withContext(Dispatchers.IO) {
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

    override suspend fun recheckGoalBlocker(
        goalId: String,
        sessionId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val session = sessionId
            val goal = storage.goals.resolve(goalId)
            val outcome =
                storage.goalRuns
                    .listByGoal(goalId)
                    .lastOrNull()
                    ?.outcome
            val fits =
                outcome != "BLOCKED(CONTEXT_WINDOW_LIMIT)" ||
                    contextFits(session, goal.objective)
            GoalBlockerResolution(storage, clock, idGenerator).resolve(goalId, session, fits)
        }

    override suspend fun updateGoalBudgets(
        goalId: String,
        budgets: com.helix.core.model.GoalBudgets,
        expectedRevision: Long?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            GoalRunCoordinator(storage, clock, idGenerator).updateBudgets(goalId, budgets, expectedRevision)
        }

    override suspend fun editGoalObjective(
        sessionId: String,
        goalId: String,
        revision: Long,
        objective: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(turnGate) {
                var changed = false
                storage.withTransaction {
                    val goal = storage.goals.find(goalId) ?: return@withTransaction
                    val owner =
                        storage.goalControls.find(goalId)?.sessionId
                            ?: storage.goalTurnBindings.sessionForGoal(goalId)
                    if (owner != null && owner != sessionId) return@withTransaction
                    val control = storage.goalControls.find(goalId)
                    val editable = goal.planId == null && goal.state in EDITABLE_STATES
                    val valid =
                        objective.isNotBlank() && objective.length <= com.helix.core.agent.Goal.MAX_OBJECTIVE_LENGTH
                    val unchanged = (control?.revision ?: 0) == revision && control?.pendingJson == null
                    // A refused edit must not claim an unbound Goal as a side effect.
                    if (unchanged && editable && valid) {
                        RoomGoalOwnership(storage).bind(goalId, sessionId)
                        storage.goals.updateObjective(goalId, objective)
                        check(storage.goalControls.settle(goalId, revision) == 1)
                        storage.auditEvents.append(
                            idGenerator(),
                            goal.correlationId,
                            "goal.objective_updated",
                            "USER",
                            """{"revision":${revision + 1}}""",
                            clock.now().toEpochMilli(),
                        )
                        changed = true
                    }
                }
                changed
            }
        }

    override fun sessionForGoal(goalId: String): String? = storage.goalTurnBindings.sessionForGoal(goalId)

    override fun goalForTurn(turnId: String): String? {
        var goalId: String? = null
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId) ?: return@withTransaction
            goalId = storage.goalRuns.resolve(binding.runId).goalId
        }
        return goalId
    }

    override fun syncReminderForTurn(turnId: String) {
        // The binding and run are one snapshot: Goal deletion cascades both.
        goalForTurn(turnId)?.let(goalReminderSync)
    }

    private companion object {
        val EDITABLE_STATES = setOf("READY", "PAUSED", "INPUT_REQUIRED", "BLOCKED")
    }
}
