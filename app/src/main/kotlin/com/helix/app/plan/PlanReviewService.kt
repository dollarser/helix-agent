package com.helix.app.plan

import com.helix.app.chat.GoalRunCoordinator
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.repository.PlanLifecycleState

/**
 * The user-facing projection of one plan for the review surface (research doc section 4.2 —
 * the doc's REVIEW_REQUIRED state; P0-B's Plan Review UI projects this). [evidenceRef] is
 * null until the plan executes, when it holds the id of the Goal that executes it.
 */
internal data class PlanReview(
    val artifact: PlanArtifact,
    val state: PlanLifecycleState,
    val evidenceRef: String?,
)

/**
 * The Plan closed loop (research doc section 4.2/4.3; HX2-05). A plan submitted by the
 * `plan.submit` tool is persisted REVIEW_REQUIRED ([PlanLifecycleState.READY]) and stays
 * INERT until the user explicitly chooses:
 *
 * - [approve]: READY -> APPROVED; yields the [PlanExecutionBinding] — the ONLY artifact an
 *   execution may reference (planId + approved version + its SHA-256);
 * - [revise]: READY -> DRAFT; the user's feedback re-drives Plan mode and the model
 *   re-submits (a new plan row);
 * - [cancel]: READY -> REJECTED;
 * - [execute]: APPROVED -> EXECUTING; begins execution in ONE transaction — it creates the Goal
 *   bound to the exact approved version (goal.planId + goal.planHash) AND moves the plan to
 *   EXECUTING together (doc 5.1: restart + double-click execute) and returns the goal id.
 *
 * Every transition is illegal-state fail-closed: a plan that is not in the state the doc's
 * lifecycle requires is refused, nothing is written. (READY is this codebase's name for the
 * doc's REVIEW_REQUIRED; REJECTED for CANCELLED — the persisted enum, HXA-014.)
 *
 * The service drives [PlanReviewPort] — the production port ([StoragePlanReviewPort]) is
 * backed by [HelixStorage]; unit tests fake it. Same seam shape as HX2-01's
 * [com.helix.app.chat.AgentTurnHost]: the loop is testable without the service or storage.
 */
internal class PlanReviewService(
    private val port: PlanReviewPort,
) {
    fun review(planId: String): PlanReview {
        val entity = port.resolveEntity(planId)
        return PlanReview(port.resolve(planId), PlanLifecycleState.valueOf(entity.state), entity.evidenceRef)
    }

    fun approve(planId: String): PlanExecutionBinding {
        val entity = requireReady(planId, "approve")
        port.transition(planId, PlanLifecycleState.APPROVED, null)
        port.audit(planId, "plan.approved", """{"version":${entity.version},"hash":"${entity.hash}"}""")
        return PlanExecutionBinding(PlanId(planId), entity.version, Sha256(entity.hash))
    }

    fun revise(planId: String) {
        requireReady(planId, "revise")
        port.transition(planId, PlanLifecycleState.DRAFT, null)
        port.audit(planId, "plan.revise_requested", "{}")
    }

    fun cancel(planId: String) {
        requireReady(planId, "cancel")
        port.transition(planId, PlanLifecycleState.REJECTED, null)
        port.audit(planId, "plan.rejected", "{}")
    }

    /** Creates the executing Goal and returns its id; [budgets] are the goal's run budgets. */
    fun execute(
        binding: PlanExecutionBinding,
        budgets: GoalBudgets,
    ): String {
        val entity = port.resolveEntity(binding.planId.value)
        val state = PlanLifecycleState.valueOf(entity.state)
        // APPROVED starts the execution; EXECUTING is a re-drive of an already-running plan, which
        // returns its bound goal (idempotent). The state is re-validated INSIDE beginExecution via
        // a conditional transition, so a concurrent execution cannot slip a second goal past this.
        require(state == PlanLifecycleState.APPROVED || state == PlanLifecycleState.EXECUTING) {
            "plan ${binding.planId.value} cannot be executed in state ${entity.state}; " +
                "only an APPROVED (or already EXECUTING) plan executes"
        }
        // The binding is the approval proof: a drifted version or hash (a revised plan, or a
        // stale binding) is refused — the execution may only reference the approved version.
        require(entity.version == binding.planVersion && entity.hash == binding.planHash.hex) {
            "the execution binding does not match the approved plan version"
        }
        val plan = port.resolve(binding.planId.value)
        // One atomic port operation: the bound Goal and the plan's EXECUTING transition (and the
        // execution_started audit) commit together, so a crash or a second click cannot leave an
        // orphaned Goal or a double execution (research doc 5.1).
        return port.beginExecution(
            objective = plan.objective,
            criteria = plan.acceptanceCriteria,
            budgets = budgets,
            planId = binding.planId.value,
            planHash = binding.planHash.hex,
        )
    }

    private fun requireReady(
        planId: String,
        action: String,
    ): PlanEntity {
        val entity = port.resolveEntity(planId)
        require(PlanLifecycleState.valueOf(entity.state) == PlanLifecycleState.READY) {
            "plan $planId cannot be $action in state ${entity.state}; " +
                "only a review-required (READY) plan accepts a user decision"
        }
        return entity
    }
}

/**
 * The storage seam [PlanReviewService] drives — see the class KDoc for the seam rationale.
 * The production implementation is [StoragePlanReviewPort]; the audit sink keeps the plan's
 * decisions in the SAME audit chain as every other user action.
 */
internal interface PlanReviewPort {
    /** The plan row, or throws when the id addresses no plan. */
    fun resolveEntity(planId: String): PlanEntity

    /** The recovered artifact (hash-verified, ADR-0001), or throws when the id addresses no plan. */
    fun resolve(planId: String): PlanArtifact

    /** Persists the lifecycle transition in one transaction with no other write. */
    fun transition(
        planId: String,
        to: PlanLifecycleState,
        evidenceRef: String?,
    )

    /**
     * Begins execution in ONE atomic transaction: creates the Goal bound to the approved plan
     * version, moves the plan to EXECUTING (with the goal as its evidenceRef) and appends the
     * execution_started audit — all commit together or none (research doc 5.1). Returns the goal id.
     */
    fun beginExecution(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: String,
        planHash: String,
    ): String

    /** Appends one audit event for the plan decision; [detailJson] carries ids/hashes only. */
    fun audit(
        planId: String,
        type: String,
        detailJson: String,
    )
}

/** The production [PlanReviewPort] over [HelixStorage] and the goal coordinator. */
internal class StoragePlanReviewPort(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : PlanReviewPort {
    override fun resolveEntity(planId: String): PlanEntity = storage.plans.resolveEntity(planId)

    override fun resolve(planId: String): PlanArtifact = storage.plans.resolve(planId)

    override fun transition(
        planId: String,
        to: PlanLifecycleState,
        evidenceRef: String?,
    ) {
        storage.withTransaction { storage.plans.updateState(planId, to.name, evidenceRef) }
    }

    override fun beginExecution(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: String,
        planHash: String,
    ): String {
        // A re-drive of a plan that is already EXECUTING returns its bound goal instead of
        // creating a second one (idempotent execution). The caller verified the binding matches
        // this plan's approved version+hash, so the bound goal is the one for this plan.
        val alreadyExecuting =
            storage.plans
                .resolveEntity(planId)
                .takeIf { it.state == PlanLifecycleState.EXECUTING.name }
        if (alreadyExecuting != null) {
            return requireNotNull(alreadyExecuting.evidenceRef) {
                "plan $planId is executing but has no bound goal"
            }
        }

        var committedGoalId: String? = null
        storage.withTransaction {
            val goalId =
                GoalRunCoordinator(storage, clock, idGenerator).saveReadyGoal(
                    objective,
                    criteria,
                    budgets,
                    PlanId(planId),
                    Sha256(planHash),
                )
            // The conditional transition is the concurrency guard: exactly one caller can move
            // the plan APPROVED -> EXECUTING. A concurrent execution that committed first makes
            // this update affect 0 rows, so the require fails, the transaction rolls back the
            // goal just inserted (no orphan), and no duplicate goal is ever created.
            require(
                storage.plans.transitionFromApproved(planId, PlanLifecycleState.EXECUTING.name, goalId) == 1,
            ) { "plan $planId left APPROVED before execution began; refusing to start a duplicate goal" }
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
        return committedGoalId ?: error("beginExecution must save the executing goal")
    }

    override fun audit(
        planId: String,
        type: String,
        detailJson: String,
    ) {
        storage.auditEvents.append(idGenerator(), planId, type, "USER", detailJson, clock.now().toEpochMilli())
    }
}
