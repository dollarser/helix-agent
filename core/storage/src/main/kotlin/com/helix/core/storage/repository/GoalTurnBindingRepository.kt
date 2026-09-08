package com.helix.core.storage.repository

import com.helix.core.storage.dao.GoalTurnBindingDao
import com.helix.core.storage.entity.GoalTurnBindingEntity

class GoalTurnBindingRepository(
    private val dao: GoalTurnBindingDao,
) {
    fun bind(
        turnId: String,
        runId: String,
    ) = dao.bind(turnId, runId)

    fun hasUnresolvedCalls(goalId: String): Boolean = dao.unresolvedForGoal(goalId) > 0

    fun hasUnsettledCalls(goalId: String): Boolean = dao.unsettledForGoal(goalId) > 0

    fun sessionForGoal(goalId: String): String? = dao.sessionsForGoal(goalId).singleOrNull()

    fun byTurn(turnId: String): GoalTurnBindingEntity? = dao.byTurn(turnId)
}
