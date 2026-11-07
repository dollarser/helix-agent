package com.helix.feature.conversation.presentation

import com.helix.core.model.GoalBudgets

data class GoalSummaryUi(
    val id: String,
    val objective: String,
    val status: GoalStatusUi,
    val criteria: List<String>,
    val budgets: GoalBudgets,
    val usage: GoalUsageUi,
    val canContinue: Boolean,
    /**
     * Durable unknown-side-effect fact: a tool call on a turn bound to this goal is
     * NEEDS_REVIEW or INTERRUPTED (HXA-202). The Tasks dashboard shows "needs review"
     * only from this fact, never from a failure in general.
     */
    val hasUnresolvedCalls: Boolean,
    val canEditBudgets: Boolean,
    val canDelete: Boolean = false,
    val revision: Long = 0,
    val canEditObjective: Boolean = false,
    /**
     * The session that owns this goal in the control registry — the Tasks dashboard's Goal
     * entry navigates to it (HXA-202). Null only when the goal has no control row.
     */
    val sessionId: String? = null,
    val roundsStarted: Int = 0,
)

data class GoalStatusUi(
    val state: String,
    val outcome: String?,
    val nextCheckpoint: Long? = null,
    val modelSummary: String? = null,
)

data class GoalUsageUi(
    val modelCalls: Int,
    val toolCalls: Int,
    val tokens: Long,
    val millis: Long,
)
