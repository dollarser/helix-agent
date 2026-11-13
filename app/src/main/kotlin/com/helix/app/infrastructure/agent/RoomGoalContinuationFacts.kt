package com.helix.app.infrastructure.agent

import com.helix.app.application.agent.GoalContinuationFacts
import com.helix.app.application.agent.GoalContinuationFactsReader
import com.helix.app.goal.toRuntimeGoal
import com.helix.core.storage.HelixStorage

/** A coherent read, not admission. TurnAdmission still rechecks inside its write transaction. */
internal class RoomGoalContinuationFacts(
    private val storage: HelixStorage,
) : GoalContinuationFactsReader {
    override fun read(
        goalId: String,
        predecessorTurnId: String,
    ): GoalContinuationFacts {
        var facts: GoalContinuationFacts? = null
        storage.withTransaction {
            val turn = storage.turns.resolve(predecessorTurnId)
            val run = storage.goalTurnBindings.byTurn(turn.id)?.let { storage.goalRuns.resolve(it.runId) }
            facts =
                GoalContinuationFacts(
                    goal = storage.goals.resolve(goalId).toRuntimeGoal(),
                    roundsUsed = RoomGoalRoundAllowance(storage).used(goalId),
                    turnState = turn.state,
                    turnErrorCode = turn.errorCode,
                    pauseRequestedAt = turn.pauseRequestedAt,
                    hasPendingEdit = storage.goalControls.find(goalId)?.pendingJson != null,
                    hasUnsettledCalls = storage.goalTurnBindings.hasUnsettledCalls(goalId),
                    hasOpenRuns = storage.goalRuns.listOpenByGoal(goalId).isNotEmpty(),
                    hasDeniedCalls = storage.toolCalls.listByTurn(turn.id).any { it.state == "DENIED" },
                    predecessorRunFinished = run?.endedAt != null,
                    predecessorRunOutcome = run?.outcome,
                    predecessorIsLatestRun =
                        run != null && storage.goalRuns
                            .listByGoal(goalId)
                            .lastOrNull()
                            ?.id == run.id,
                )
        }
        return requireNotNull(facts)
    }
}
