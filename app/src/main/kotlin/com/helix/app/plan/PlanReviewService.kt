package com.helix.app.plan

import com.helix.app.infrastructure.agent.RoomGoalRunCommands
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.PlanLifecycleState
import com.helix.feature.conversation.application.goals.ConversationPlans
import com.helix.feature.conversation.application.goals.PlanReview
import com.helix.feature.conversation.application.goals.PlanReviewState

/**
 * Explicit Plan decisions. A review snapshot is inert; only approval returns an execution binding.
 * Storage owns atomic decision/audit and bound-Goal creation, including checks against stale snapshots.
 */
internal class PlanReviewService(
    private val port: PlanReviewPort,
) : ConversationPlans {
    override fun review(planId: String): PlanReview = port.review(planId)

    override fun approve(planId: String): PlanExecutionBinding {
        val plan = requireReady(planId, "approve")
        port.decide(plan, PlanReviewDecision.APPROVE)
        return PlanExecutionBinding(plan.id, plan.version, plan.sha256())
    }

    override fun revise(planId: String) {
        port.decide(requireReady(planId, "revise"), PlanReviewDecision.REVISE)
    }

    override fun cancel(planId: String) {
        port.decide(requireReady(planId, "cancel"), PlanReviewDecision.CANCEL)
    }

    override fun execute(
        binding: PlanExecutionBinding,
        budgets: GoalBudgets,
    ): String {
        val review = port.review(binding.planId.value)
        require(review.state == PlanReviewState.APPROVED || review.state == PlanReviewState.EXECUTING) {
            "plan ${binding.planId.value} cannot be executed in state ${review.state}; " +
                "only an APPROVED (or already EXECUTING) plan executes"
        }
        require(review.artifact.version == binding.planVersion && review.artifact.sha256() == binding.planHash) {
            "the execution binding does not match the approved plan version"
        }
        // The adapter repeats these checks in the transaction that creates/reuses the bound Goal.
        return port.beginExecution(binding, budgets)
    }

    private fun requireReady(
        planId: String,
        action: String,
    ): PlanArtifact {
        val review = port.review(planId)
        require(review.state == PlanReviewState.READY) {
            "plan $planId cannot be $action in state ${review.state}; " +
                "only a review-required (READY) plan accepts a user decision"
        }
        return review.artifact
    }
}

internal enum class PlanReviewDecision(
    val target: PlanLifecycleState,
    val event: String,
) {
    APPROVE(PlanLifecycleState.APPROVED, "plan.approved"),
    REVISE(PlanLifecycleState.DRAFT, "plan.revise_requested"),
    CANCEL(PlanLifecycleState.REJECTED, "plan.rejected"),
}

/** Concrete persistence operations, not caller-supplied transaction bodies or Room DTOs. */
internal interface PlanReviewPort {
    fun review(planId: String): PlanReview

    /** Compare the reviewed artifact and record the decision + audit in one transaction. */
    fun decide(
        expected: PlanArtifact,
        decision: PlanReviewDecision,
    )

    /** Recheck approval/version/hash and create/reuse its single bound Goal in one transaction. */
    fun beginExecution(
        binding: PlanExecutionBinding,
        budgets: GoalBudgets,
    ): String
}

internal class StoragePlanReviewPort(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : PlanReviewPort {
    override fun review(planId: String): PlanReview {
        var result: PlanReview? = null
        storage.withTransaction {
            val row = storage.plans.resolveEntity(planId)
            result = PlanReview(storage.plans.resolve(planId), PlanReviewState.valueOf(row.state), row.evidenceRef)
        }
        return checkNotNull(result)
    }

    override fun decide(
        expected: PlanArtifact,
        decision: PlanReviewDecision,
    ) {
        storage.withTransaction {
            val row = storage.plans.resolveEntity(expected.id.value)
            require(row.state == PlanLifecycleState.READY.name) { "Plan is no longer review-required" }
            require(row.version == expected.version && row.hash == expected.sha256().hex) { "Reviewed plan changed" }
            storage.plans.review(expected.id.value, decision.target.name, null)
            val detail =
                if (decision == PlanReviewDecision.APPROVE) {
                    """{"version":${row.version},"hash":"${row.hash}"}"""
                } else {
                    "{}"
                }
            storage.auditEvents.append(
                idGenerator(),
                expected.id.value,
                decision.event,
                "USER",
                detail,
                clock.now().toEpochMilli(),
            )
        }
    }

    override fun beginExecution(
        binding: PlanExecutionBinding,
        budgets: GoalBudgets,
    ): String {
        var committedGoalId: String? = null
        storage.withTransaction {
            val planId = binding.planId.value
            val row = storage.plans.resolveEntity(planId)
            require(row.version == binding.planVersion && row.hash == binding.planHash.hex) {
                "The execution binding no longer matches the approved plan"
            }
            val plan = storage.plans.resolve(planId)
            if (row.state == PlanLifecycleState.EXECUTING.name) {
                // Re-drives are idempotent inside the same snapshot, including concurrent clicks.
                val goalId = requireNotNull(row.evidenceRef) { "Executing plan has no bound Goal" }
                val goal = storage.goals.resolve(goalId)
                check(goal.planId == planId && goal.planHash == binding.planHash.hex) { "Plan Goal binding changed" }
                committedGoalId = goalId
            } else {
                require(row.state == PlanLifecycleState.APPROVED.name) { "Plan is no longer approved" }
                val goalId =
                    RoomGoalRunCommands(storage, clock, idGenerator).create(
                        plan.objective,
                        plan.acceptanceCriteria,
                        budgets,
                        binding.planId,
                        binding.planHash,
                    )
                require(storage.plans.transitionFromApproved(planId, PlanLifecycleState.EXECUTING.name, goalId) == 1) {
                    "Plan left APPROVED before execution began"
                }
                storage.auditEvents.append(
                    idGenerator(),
                    planId,
                    "plan.execution_started",
                    "USER",
                    """{"goalId":"$goalId"}""",
                    clock.now().toEpochMilli(),
                )
                committedGoalId = goalId
            }
        }
        return checkNotNull(committedGoalId)
    }
}
