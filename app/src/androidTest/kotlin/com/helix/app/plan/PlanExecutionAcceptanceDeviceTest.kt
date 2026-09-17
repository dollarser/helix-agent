package com.helix.app.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-192 device acceptance (durable invariants, no model provider): the plan-execute
 * idempotency / version-drift / approval-gate rules proven against the REAL [StoragePlanReviewPort]
 * and Room — not the hand-written FakePort of the PlanReviewServiceTest unit suite, which models
 * the concurrency guard rather than exercising Room's conditional `transitionFromApproved`.
 *
 * The production [PlanReviewService] is driven through its service seam (reuse, not a new Plan
 * Engine). `execute` needs no bound model provider (unlike ChatService.executeApprovedPlan, which
 * additionally gates on the session's provider) — that is precisely what lets these storage-level
 * invariants run headless. The real UI closed loop is PlanExecuteCloseLoopDeviceTest; the
 * process-restart recovery path is PlanExecutionRecoveryDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class PlanExecutionAcceptanceDeviceTest {
    private val budgets = GoalBudgets(3, 10, 128_000L, 3_600_000L, 600_000L, 2)

    @Test
    fun anApprovedPlanExecutesOnceAndAReDriveReturnsTheSameBoundGoal() {
        val container = container()
        val service = serviceFor(container)
        val planId = "plan-exec-once-${System.nanoTime()}"
        seedReadyPlan(container, planId)
        try {
            val binding = service.approve(planId)
            val first = service.execute(binding, budgets)
            // The plan is EXECUTING and its evidenceRef is the bound goal's id.
            val planEntity = container.storage.plans.resolveEntity(planId)
            assertEquals("EXECUTING", planEntity.state)
            assertEquals(first, planEntity.evidenceRef)
            // Exactly one goal, bound to the EXACT approved version hash.
            val goalEntity = container.storage.goals.resolveEntity(first)
            assertEquals(planId, goalEntity.planId)
            assertEquals(planEntity.hash, goalEntity.planHash)
            assertEquals(1, container.storage.goals.countByPlan(planId))
            // A re-drive (double-click / re-submission) returns the SAME goal — no second goal.
            assertEquals(first, service.execute(binding, budgets))
            assertEquals(1, container.storage.goals.countByPlan(planId))
        } finally {
            cleanup(container, planId)
        }
    }

    @Test
    fun executeRefusesABindingThatDriftedFromTheApprovedVersion() {
        val container = container()
        val service = serviceFor(container)
        val planId = "plan-exec-drift-${System.nanoTime()}"
        val artifact = seedReadyPlan(container, planId)
        try {
            service.approve(planId)
            // Same version, but the hash of the NEXT version — a binding that no longer matches
            // the reviewed (approved) version must be refused, with nothing written.
            val drifted =
                PlanExecutionBinding(PlanId(planId), artifact.version, artifact.withNextVersion().sha256())
            assertThrows(IllegalArgumentException::class.java) { service.execute(drifted, budgets) }
            // Nothing was written: no goal, and the plan was left APPROVED (not EXECUTING).
            assertEquals(0, container.storage.goals.countByPlan(planId))
            assertEquals(
                "APPROVED",
                container.storage.plans
                    .resolveEntity(planId)
                    .state,
            )
        } finally {
            cleanup(container, planId)
        }
    }

    @Test
    fun executeRefusesAPlanThatHasNotBeenApproved() {
        val container = container()
        val service = serviceFor(container)
        val planId = "plan-exec-unapproved-${System.nanoTime()}"
        val artifact = seedReadyPlan(container, planId)
        try {
            // A READY (never-approved) plan cannot execute even with a version-accurate binding.
            val binding = PlanExecutionBinding(PlanId(planId), artifact.version, artifact.sha256())
            assertThrows(IllegalArgumentException::class.java) { service.execute(binding, budgets) }
            // Nothing was written: no goal, and the plan was left READY.
            assertEquals(0, container.storage.goals.countByPlan(planId))
            assertEquals(
                "READY",
                container.storage.plans
                    .resolveEntity(planId)
                    .state,
            )
        } finally {
            cleanup(container, planId)
        }
    }

    // ---------------------------------------------------------------- fixtures

    private fun container(): AppContainer =
        (ApplicationProvider.getApplicationContext<android.app.Application>() as HelixApplication).appContainer

    private fun serviceFor(container: AppContainer): PlanReviewService {
        var seq = 0L
        return PlanReviewService(
            StoragePlanReviewPort(container.storage, SystemClock(), { "pe-${System.nanoTime()}-${seq++}" }),
        )
    }

    private fun seedReadyPlan(
        container: AppContainer,
        planId: String,
    ): PlanArtifact {
        val artifact =
            PlanArtifact(
                id = PlanId(planId),
                objective = "Plan execute acceptance test",
                assumptions = emptyList(),
                steps = listOf(PlanStep("step one", "do the thing")),
                acceptanceCriteria = listOf("the thing is done"),
                risks = emptyList(),
                version = 1,
            )
        container.storage.withTransaction { container.storage.plans.save(artifact, "READY", null) }
        return artifact
    }

    /** Removes the fixture goal then plan so a per-run id never leaks into a later test. */
    private fun cleanup(
        container: AppContainer,
        planId: String,
    ) {
        val evidenceRef =
            runCatching {
                container.storage.plans
                    .resolveEntity(planId)
                    .evidenceRef
            }.getOrNull()
        if (evidenceRef != null) runCatching { container.storage.goals.delete(evidenceRef) }
        runCatching { container.storage.plans.delete(planId) }
    }
}
