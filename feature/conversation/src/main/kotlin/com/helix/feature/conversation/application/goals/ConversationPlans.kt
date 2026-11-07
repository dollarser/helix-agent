package com.helix.feature.conversation.application.goals

import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding

/** A verified artifact and the user-visible lifecycle, never a Room row or an execution grant. */
data class PlanReview(
    val artifact: PlanArtifact,
    val state: PlanReviewState,
    val evidenceRef: String?,
)

enum class PlanReviewState { DRAFT, READY, APPROVED, EXECUTING, SUPERSEDED, REJECTED }

/** Explicit review decisions. Only approve yields a binding; execute must revalidate that exact version. */
interface ConversationPlans {
    fun review(planId: String): PlanReview

    fun approve(planId: String): PlanExecutionBinding

    fun revise(planId: String)

    fun cancel(planId: String)

    /** Atomically bind the approved plan to one Goal; starting its Turn is a separate admission. */
    fun execute(
        binding: PlanExecutionBinding,
        budgets: GoalBudgets,
    ): String
}
