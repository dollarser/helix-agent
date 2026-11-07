package com.helix.feature.conversation.application.goals

import com.helix.core.model.GoalBudgets
import com.helix.feature.conversation.presentation.GoalSummaryUi

/** Goal decisions and durable queries. These operations never admit a Turn or grant tool authority. */
interface ConversationGoals {
    suspend fun createGoal(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
    ): String

    suspend fun goalSummaries(sessionId: String?): List<GoalSummaryUi>

    suspend fun goalSummariesAll(): List<GoalSummaryUi>

    suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ): Boolean

    suspend fun recheckGoalBlocker(
        goalId: String,
        sessionId: String,
    ): Boolean

    suspend fun updateGoalBudgets(
        goalId: String,
        budgets: GoalBudgets,
        expectedRevision: Long? = null,
    ): Boolean

    suspend fun editGoalObjective(
        sessionId: String,
        goalId: String,
        revision: Long,
        objective: String,
    ): Boolean

    fun sessionForGoal(goalId: String): String?

    fun goalForTurn(turnId: String): String?

    /** Called after durable settlement; failures remain visible to the caller. */
    fun syncReminderForTurn(turnId: String)
}
