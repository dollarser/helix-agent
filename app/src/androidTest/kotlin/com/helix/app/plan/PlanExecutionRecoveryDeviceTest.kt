package com.helix.app.plan

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.Sha256
import com.helix.core.model.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-192 device acceptance: a plan left EXECUTING across a REAL process death (not a re-seed)
 * must not re-execute. The owned runner (scripts/debug/2026-09-17/hxa192) drives the two-phase
 * protocol through run-owned-emulator.py: phase `setup` seeds a fresh plan, drives it
 * READY -> APPROVED -> EXECUTING (creating its bound goal), writes this process's pid to the
 * durable no_backup/recovery-device-pid marker, and kills the process (the runner expects "process
 * crashed"); phase `verify` (a fresh process, installed once so the rows survive) asserts the pid
 * actually changed, the plan is still EXECUTING with its single bound goal, and a re-drive returns
 * that SAME goal — the already-executing early return plus Room's conditional `transitionFromApproved`
 * guard keep a restart from minting a second goal. Run with no phase argument it is a normal
 * in-process idempotency regression.
 *
 * A single-method class on purpose: the runner's setup phase instruments this whole class with
 * recoveryPhase=setup, and it must deterministically reach the process kill. Reuses the production
 * PlanReviewService + StoragePlanReviewPort (no new Plan Engine).
 */
@RunWith(AndroidJUnit4::class)
class PlanExecutionRecoveryDeviceTest {
    private val budgets = GoalBudgets(3, 10, 128_000L, 3_600_000L, 600_000L, 2)

    /**
     * FIXED id (not per-run): the setup and verify phases are separate `am instrument` runs, so both
     * must address the SAME plan. The id is unique enough to not collide with any other fixture, and
     * the finally deletes the goal + plan so a bare in-process run does not leak.
     */
    private val planId = "plan-exec-recovery"

    @Test
    fun aPlanLeftExecutingSurvivesRestartWithoutReExecuting() {
        val context =
            ApplicationProvider.getApplicationContext<android.app.Application>() as HelixApplication
        val container = context.appContainer
        val marker = context.noBackupFilesDir.resolve("recovery-device-pid")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase != "verify") {
            // Persist the EXECUTING state that must survive the restart (committed before the marker).
            if (container.storage.plans
                    .list()
                    .none { it.id == planId }
            ) {
                seedReadyPlan(container, planId)
            }
            if (container.storage.plans
                    .resolveEntity(planId)
                    .state == "READY"
            ) {
                val service = serviceFor(container)
                service.execute(service.approve(planId), budgets)
            }
            // The durable identity of THIS process, readable after the kill via run-as.
            marker.writeText(Process.myPid().toString())
            if (phase == "setup") {
                Process.killProcess(Process.myPid())
            }
        }
        if (phase == "setup") return
        try {
            if (phase == "verify") {
                assertNotEquals(
                    "the process must have actually restarted (different pid)",
                    marker.readText().toInt(),
                    Process.myPid(),
                )
            }
            val service = serviceFor(container)
            val planEntity = container.storage.plans.resolveEntity(planId)
            assertEquals("the plan must still be EXECUTING after the restart", "EXECUTING", planEntity.state)
            val boundGoal = checkNotNull(planEntity.evidenceRef) { "the executing plan must bind its goal" }
            assertEquals(1, container.storage.goals.countByPlan(planId))
            // A re-drive after the restart returns the SAME bound goal — no duplicate execution.
            val reDriven =
                service.execute(
                    PlanExecutionBinding(PlanId(planId), planEntity.version, Sha256(planEntity.hash)),
                    budgets,
                )
            assertEquals(boundGoal, reDriven)
            assertEquals(1, container.storage.goals.countByPlan(planId))
        } finally {
            val evidenceRef =
                runCatching {
                    container.storage.plans
                        .resolveEntity(planId)
                        .evidenceRef
                }.getOrNull()
            if (evidenceRef != null) runCatching { container.storage.goals.delete(evidenceRef) }
            runCatching { container.storage.plans.delete(planId) }
            marker.delete()
        }
    }

    // ---------------------------------------------------------------- fixtures

    private fun serviceFor(container: AppContainer): PlanReviewService {
        var seq = 0L
        return PlanReviewService(
            StoragePlanReviewPort(container.storage, SystemClock(), { "rec-${System.nanoTime()}-${seq++}" }),
        )
    }

    private fun seedReadyPlan(
        container: AppContainer,
        planId: String,
    ) {
        val artifact =
            PlanArtifact(
                id = PlanId(planId),
                objective = "Plan execute recovery test",
                assumptions = emptyList(),
                steps = listOf(PlanStep("step one", "do the thing")),
                acceptanceCriteria = listOf("the thing is done"),
                risks = emptyList(),
                version = 1,
            )
        container.storage.withTransaction { container.storage.plans.save(artifact, "READY", null) }
    }
}
