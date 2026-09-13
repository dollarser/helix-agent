package com.helix.app.plan

import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.Sha256
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.repository.PlanLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlanReviewService] (research doc section 4.2/4.3; HX2-05): the plan
 * lifecycle is the closed loop — REVIEW_REQUIRED (READY) accepts exactly the user's
 * approve / revise / cancel; only APPROVED with a matching binding executes; every illegal
 * state is fail-closed with nothing written. Driven through a hand-written [PlanReviewPort]
 * fake — no storage stack (the same seam shape as HX2-01's AgentTurnHost tests).
 */
class PlanReviewServiceTest {
    private val planId = "plan-1"
    private val artifact =
        PlanArtifact(
            id = PlanId(planId),
            objective = "Migrate the storage layer",
            assumptions = emptyList(),
            steps = listOf(PlanStep("Read the spec", "study it"), PlanStep("Write the migration", "code it")),
            acceptanceCriteria = listOf("rows survive a schema migration"),
            risks = emptyList(),
            version = 1,
        )
    private val budgets = GoalBudgets(3, 10, 128_000L, 3_600_000L, 600_000L, 2)

    private fun entity(
        state: PlanLifecycleState,
        evidenceRef: String? = null,
    ): PlanEntity =
        PlanEntity(
            id = planId,
            objective = artifact.objective,
            assumptionsJson = "[]",
            acceptanceCriteriaJson = artifact.acceptanceCriteria.map { "\"$it\"" }.joinToString(",", "[", "]"),
            risksJson = "[]",
            version = artifact.version,
            hash = artifact.sha256().hex,
            state = state.name,
            evidenceRef = evidenceRef,
        )

    private class FakePort(
        private var row: PlanEntity,
        private val artifact: PlanArtifact,
    ) : PlanReviewPort {
        val transitions = mutableListOf<Pair<PlanLifecycleState, String?>>()
        val audits = mutableListOf<Pair<String, String>>()

        data class GoalCreation(
            val objective: String,
            val criteria: List<String>,
            val planId: String,
            val planHash: String,
        )

        var goalCreation: GoalCreation? = null
        val goalId = "goal-1"

        override fun resolveEntity(planId: String): PlanEntity =
            row.takeIf { it.id == planId } ?: throw IllegalArgumentException("plan not found: $planId")

        override fun resolve(planId: String): PlanArtifact =
            artifact.takeIf { it.id.value == planId } ?: throw IllegalArgumentException("plan not found: $planId")

        override fun transition(
            planId: String,
            to: PlanLifecycleState,
            evidenceRef: String?,
        ) {
            transitions += to to evidenceRef
            row = row.copy(state = to.name, evidenceRef = evidenceRef)
        }

        override fun createExecutingGoal(
            objective: String,
            criteria: List<String>,
            budgets: GoalBudgets,
            planId: String,
            planHash: String,
        ): String {
            goalCreation = GoalCreation(objective, criteria, planId, planHash)
            return goalId
        }

        override fun audit(
            planId: String,
            type: String,
            detailJson: String,
        ) {
            audits += type to detailJson
        }
    }

    private fun readyFixture() =
        run {
            val port = FakePort(entity(PlanLifecycleState.READY), artifact)
            Triple(PlanReviewService(port), port, port.goalId)
        }

    // --- approve: the ONLY path to an execution binding ---

    @Test
    fun approveOfAReadyPlanYieldsTheExactBindingAndMovesToApproved() {
        val (service, port, _) = readyFixture()
        val binding = service.approve(planId)

        assertEquals(PlanId(planId), binding.planId)
        assertEquals(1, binding.planVersion)
        assertEquals(artifact.sha256(), binding.planHash)
        assertEquals(listOf<Pair<PlanLifecycleState, String?>>(PlanLifecycleState.APPROVED to null), port.transitions)
        assertEquals(listOf("plan.approved"), port.audits.map { it.first })
        assertEquals(PlanLifecycleState.APPROVED, port.resolveEntity(planId).state.let(PlanLifecycleState::valueOf))
    }

    @Test
    fun approveRefusesAPlanThatIsNotReviewRequired() {
        for (state in listOf(PlanLifecycleState.DRAFT, PlanLifecycleState.APPROVED, PlanLifecycleState.EXECUTING)) {
            val port = FakePort(entity(state), artifact)
            val service = PlanReviewService(port)
            assertThrows(IllegalArgumentException::class.java) { service.approve(planId) }
            assertTrue(port.transitions.isEmpty())
            assertTrue(port.audits.isEmpty())
        }
    }

    // --- revise / cancel ---

    @Test
    fun reviseSendsThePlanBackToPlanning() {
        val (service, port, _) = readyFixture()
        service.revise(planId)
        assertEquals(listOf(PlanLifecycleState.DRAFT), port.transitions.map { it.first })
        assertEquals(listOf("plan.revise_requested"), port.audits.map { it.first })
    }

    @Test
    fun cancelRejectsAReadyPlan() {
        val (service, port, _) = readyFixture()
        service.cancel(planId)
        assertEquals(listOf(PlanLifecycleState.REJECTED), port.transitions.map { it.first })
        assertEquals(listOf("plan.rejected"), port.audits.map { it.first })
    }

    @Test
    fun reviseAndCancelRefusePlansThatAreNotReviewRequired() {
        val port = FakePort(entity(PlanLifecycleState.APPROVED), artifact)
        val service = PlanReviewService(port)
        assertThrows(IllegalArgumentException::class.java) { service.revise(planId) }
        assertThrows(IllegalArgumentException::class.java) { service.cancel(planId) }
        assertTrue(port.transitions.isEmpty())
    }

    // --- execute: only an approved plan, at the bound version ---

    @Test
    fun executeOfAnApprovedPlanCreatesTheBoundGoalAndMarksItExecuting() {
        val port = FakePort(entity(PlanLifecycleState.APPROVED), artifact)
        val service = PlanReviewService(port)
        val binding = PlanExecutionBinding(PlanId(planId), artifact.version, artifact.sha256())

        val goalId = service.execute(binding, budgets)

        assertEquals(port.goalId, goalId)
        val creation =
            port.goalCreation
                ?: throw AssertionError("no goal was created")
        assertEquals(artifact.objective, creation.objective)
        assertEquals(artifact.acceptanceCriteria, creation.criteria)
        assertEquals(planId, creation.planId)
        assertEquals(artifact.sha256().hex, creation.planHash)
        // The executing Goal is the plan's evidenceRef; the state is EXECUTING.
        assertEquals(
            listOf<Pair<PlanLifecycleState, String?>>(PlanLifecycleState.EXECUTING to port.goalId),
            port.transitions,
        )
        assertEquals(listOf("plan.execution_started"), port.audits.map { it.first })
    }

    @Test
    fun executeRefusesABindingThatDriftedFromTheApprovedVersion() {
        val port = FakePort(entity(PlanLifecycleState.APPROVED), artifact)
        val service = PlanReviewService(port)
        val drifted = PlanExecutionBinding(PlanId(planId), artifact.version, artifact.withNextVersion().sha256())
        assertThrows(IllegalArgumentException::class.java) { service.execute(drifted, budgets) }
        assertNull(port.goalCreation)
        assertTrue(port.transitions.isEmpty())
    }

    @Test
    fun executeRefusesAPlanThatIsNotApproved() {
        for (state in listOf(PlanLifecycleState.READY, PlanLifecycleState.DRAFT, PlanLifecycleState.REJECTED)) {
            val port = FakePort(entity(state), artifact)
            val service = PlanReviewService(port)
            val binding = PlanExecutionBinding(PlanId(planId), artifact.version, artifact.sha256())
            assertThrows(IllegalArgumentException::class.java) { service.execute(binding, budgets) }
            assertTrue(port.transitions.isEmpty())
        }
    }

    // --- review projection ---

    @Test
    fun reviewProjectsTheCurrentStateAndEvidenceRef() {
        val port = FakePort(entity(PlanLifecycleState.EXECUTING, evidenceRef = "goal-9"), artifact)
        val review = PlanReviewService(port).review(planId)
        assertEquals(artifact, review.artifact)
        assertEquals(PlanLifecycleState.EXECUTING, review.state)
        assertEquals("goal-9", review.evidenceRef)
    }
}
