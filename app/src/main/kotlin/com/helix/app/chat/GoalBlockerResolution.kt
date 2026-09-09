package com.helix.app.chat

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage

/** Explicit repair action. No run, model call, budget reset or evidence completion is created. */
internal class GoalBlockerResolution(
    private val storage: HelixStorage,
    private val clock: com.helix.core.model.Clock,
    private val idGenerator: () -> String,
) {
    fun resolve(
        goalId: String,
        sessionId: String,
        contextFits: Boolean,
    ): Boolean {
        var resolved = false
        storage.withTransaction {
            if (storage.goalTurnBindings.sessionForGoal(goalId) != sessionId) return@withTransaction
            val goal = storage.goals.resolve(goalId).toRuntimeGoal()
            if (goal.state != GoalState.BLOCKED) return@withTransaction
            if (storage.goalRuns.listOpenByGoal(goalId).isNotEmpty() ||
                storage.goalTurnBindings.hasUnsettledCalls(goalId)
            ) {
                return@withTransaction
            }
            val outcome =
                storage.goalRuns
                    .listByGoal(goalId)
                    .lastOrNull()
                    ?.outcome
            if (outcome == "BLOCKED(CONTEXT_WINDOW_LIMIT)" && !contextFits) return@withTransaction
            val step = GoalReducer.reduce(goal, GoalEvent.BlockerResolved)
            if (!step.ignored) {
                storage.goals.updateGoal(step.state.toStoredGoal())
                storage.auditEvents.append(
                    idGenerator(),
                    goal.correlationId.value,
                    "goal.blocker_resolved",
                    "USER",
                    """{"state":"PAUSED","runCount":${goal.runCount}}""",
                    clock.now().toEpochMilli(),
                )
                resolved = true
            }
        }
        return resolved
    }
}
