package com.helix.app.chat

import com.helix.app.goal.toRuntimeGoal
import com.helix.core.agent.GoalEffect
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.storage.HelixStorage

internal data class GoalSummaryUi(
    val id: String,
    val objective: String,
    val status: GoalStatusUi,
    val criteria: List<String>,
    val satisfiedCriteria: Int,
    val budgets: GoalBudgets,
    val usage: GoalUsageUi,
    val canContinue: Boolean,
    val canEditBudgets: Boolean,
    val canDelete: Boolean = false,
)

internal data class GoalStatusUi(
    val state: String,
    val outcome: String?,
    val nextCheckpoint: Long? = null,
)

internal data class GoalUsageUi(
    val modelCalls: Int,
    val toolCalls: Int,
    val tokens: Long,
    val millis: Long,
)

/** Project persisted facts for the service; UI never reads storage or infers completion from model text. */
internal class GoalSummaryQuery(
    private val storage: HelixStorage,
) {
    fun forSession(sessionId: String): List<GoalSummaryUi> {
        val runIds =
            storage.turns
                .listBySession(
                    sessionId,
                ).mapNotNull { storage.goalTurnBindings.byTurn(it.id)?.runId }
                .toSet()
        return storage.goals.list().mapNotNull { entity ->
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
                GoalStatusUi(goal.state, runs.maxByOrNull { it.startedAt }?.outcome, goal.nextCheckpoint),
                goal.criteria.map { it.description },
                runtime.criteria.size - runtime.unsatisfiedCriteria.size,
                goal.budgets,
                GoalUsageUi(goal.modelCalls, goal.toolCalls, goal.totalTokens, goal.runTimeMillis),
                canStart && !storage.goalTurnBindings.hasUnresolvedCalls(goal.id) && runs.none { it.endedAt == null },
                goal.state in setOf("PAUSED", "INPUT_REQUIRED"),
                goal.state != "RUNNING" && runs.none { it.endedAt == null },
            )
        }
    }
}
