package com.helix.app.chat

import com.helix.app.application.agent.GoalRoundAllowance
import com.helix.app.goal.goalModelReport
import com.helix.app.goal.toRuntimeGoal
import com.helix.core.agent.GoalEffect
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.GoalWakeReason
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.presentation.GoalStatusUi
import com.helix.feature.conversation.presentation.GoalSummaryUi
import com.helix.feature.conversation.presentation.GoalUsageUi

/** Project persisted facts for the service; UI never reads storage or infers completion from model text. */
internal class GoalSummaryQuery(
    private val storage: HelixStorage,
) {
    fun forSession(sessionId: String): List<GoalSummaryUi> {
        var snapshot = emptyList<GoalSummaryUi>()
        storage.withTransaction { snapshot = readSnapshot(sessionId) }
        return snapshot
    }

    /**
     * Cross-session view for the Tasks dashboard (doc section 13): every goal, no session
     * filter. [GoalStatusUi.modelSummary] is null here — the model report is bound to a
     * turn of a concrete session, which this view does not address.
     */
    fun forAll(): List<GoalSummaryUi> {
        var snapshot = emptyList<GoalSummaryUi>()
        storage.withTransaction { snapshot = readAll() }
        return snapshot
    }

    private fun canEditObjective(goal: com.helix.core.storage.mapping.StoredGoal): Boolean =
        goal.planId == null && goal.state in setOf("READY", "PAUSED", "INPUT_REQUIRED", "BLOCKED") &&
            storage.goalControls.find(goal.id)?.pendingTurnId == null

    private fun readAll(): List<GoalSummaryUi> =
        storage.goals.list().map { entity ->
            val goal = storage.goals.resolve(entity.id)
            val runtime = goal.toRuntimeGoal()
            val runs = storage.goalRuns.listByGoal(entity.id)
            val canStart =
                GoalReducer
                    .reduce(runtime, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
                    .effects
                    .any { it is GoalEffect.StartRun }
            val unresolvedCalls = storage.goalTurnBindings.hasUnresolvedCalls(entity.id)
            val ownerSession = storage.goalControls.find(entity.id)?.sessionId
            GoalSummaryUi(
                goal.id,
                goal.objective,
                GoalStatusUi(goal.state, goal.finishReason ?: runs.lastOrNull()?.outcome, goal.nextCheckpoint, null),
                goal.criteria.map { it.description },
                goal.budgets,
                GoalUsageUi(goal.modelCalls, goal.toolCalls, goal.totalTokens, goal.runTimeMillis),
                canStart && !unresolvedCalls && runs.none { it.endedAt == null },
                unresolvedCalls,
                goal.state in setOf("READY", "PAUSED", "INPUT_REQUIRED", "BLOCKED") &&
                    storage.goalControls.find(goal.id)?.pendingTurnId == null,
                goal.state != "RUNNING" && runs.none { it.endedAt == null } &&
                    storage.goalControls.find(goal.id)?.pendingTurnId == null,
                storage.goalControls.find(goal.id)?.revision ?: 0,
                canEditObjective(goal),
                sessionId = ownerSession,
                roundsStarted = GoalRoundAllowance(storage).used(goal.id),
            )
        }

    private fun readSnapshot(sessionId: String): List<GoalSummaryUi> {
        val runIds =
            storage.turns
                .listBySession(
                    sessionId,
                ).mapNotNull { storage.goalTurnBindings.byTurn(it.id)?.runId }
                .toSet()
        return storage.goals.list().mapNotNull { entity ->
            val owner = storage.goalControls.find(entity.id)?.sessionId
            if (owner != null && owner != sessionId) return@mapNotNull null
            val runs = storage.goalRuns.listByGoal(entity.id)
            if (runs.isNotEmpty() && runs.none { it.id in runIds }) return@mapNotNull null
            val goal = storage.goals.resolve(entity.id)
            val runtime = goal.toRuntimeGoal()
            val canStart =
                GoalReducer
                    .reduce(runtime, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
                    .effects
                    .any { it is GoalEffect.StartRun }
            GoalSummaryUi(
                goal.id,
                goal.objective,
                GoalStatusUi(
                    goal.state,
                    goal.finishReason ?: runs.lastOrNull()?.outcome,
                    goal.nextCheckpoint,
                    runs.lastOrNull()?.let { latest ->
                        storage.turns
                            .listBySession(sessionId)
                            .lastOrNull { storage.goalTurnBindings.byTurn(it.id)?.runId == latest.id }
                            ?.let { storage.goalModelReport(it.id)?.summary }
                    },
                ),
                goal.criteria.map { it.description },
                goal.budgets,
                GoalUsageUi(goal.modelCalls, goal.toolCalls, goal.totalTokens, goal.runTimeMillis),
                canStart && !storage.goalTurnBindings.hasUnresolvedCalls(goal.id) && runs.none { it.endedAt == null },
                // The cross-session "needs review" projection is a Tasks-dashboard concern;
                // the session-scoped view renders goal state directly and does not carry it.
                false,
                goal.state in setOf("READY", "PAUSED", "INPUT_REQUIRED", "BLOCKED") &&
                    storage.goalControls.find(goal.id)?.pendingTurnId == null,
                goal.state != "RUNNING" && runs.none { it.endedAt == null } &&
                    storage.goalControls.find(goal.id)?.pendingTurnId == null,
                storage.goalControls.find(goal.id)?.revision ?: 0,
                canEditObjective(goal),
                sessionId = owner,
                roundsStarted = GoalRoundAllowance(storage).used(goal.id),
            )
        }
    }
}
