package com.helix.app.infrastructure.agent

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.GoalControlEntity
import com.helix.core.storage.mapping.StoredGoal

/** Room-owned session/Goal identity queries shared by tool edits and atomic admission. */
internal class RoomGoalOwnership(
    private val storage: HelixStorage,
) {
    fun bind(
        goalId: String,
        sessionId: String,
    ) {
        // Reject a stale/deleted Goal through the caller's normal domain-error path,
        // before a control-row insert can fail with an uncaught Room foreign-key error.
        storage.goals.resolve(goalId)
        val existing = storage.goalControls.find(goalId)
        if (existing == null) {
            require(storage.goalTurnBindings.sessionForGoal(goalId).let { it == null || it == sessionId })
            storage.goalControls.insert(GoalControlEntity(goalId, sessionId, 0, null, null))
        } else {
            require(existing.sessionId == sessionId) { "Goal belongs to another session" }
        }
    }

    fun current(sessionId: String): StoredGoal? {
        val owned = storage.goalControls.bySession(sessionId).map { storage.goals.resolve(it.goalId) }
        val unfinished = owned.filter { it.state !in setOf("COMPLETED", "FAILED", "CANCELLED") }
        val ids = unfinished.map { it.id }.toSet()
        val latest =
            storage.turns.listBySession(sessionId).asReversed().firstNotNullOfOrNull { turn ->
                storage.goalTurnBindings.byTurn(turn.id)?.let { binding ->
                    storage.goalRuns
                        .resolve(binding.runId)
                        .goalId
                        .takeIf { it in ids }
                }
            }
        return unfinished.firstOrNull { it.id == latest } ?: unfinished.firstOrNull() ?: owned.firstOrNull()
    }

    fun unfinishedForSession(sessionId: String): String? {
        val runIds =
            storage.turns
                .listBySession(sessionId)
                .mapNotNull { storage.goalTurnBindings.byTurn(it.id)?.runId }
                .toSet()
        return storage.goals
            .list()
            .firstOrNull { entity ->
                val owner = storage.goalControls.find(entity.id)?.sessionId
                val runs = storage.goalRuns.listByGoal(entity.id)
                (owner == null || owner == sessionId) &&
                    (runs.isEmpty() || runs.any { it.id in runIds }) &&
                    storage.goals.resolve(entity.id).state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
                    storage.goalTurnBindings.sessionForGoal(entity.id) == sessionId
            }?.id
    }
}
