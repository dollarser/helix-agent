package com.helix.app.infrastructure.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.agent.RoomGoalRunCommands
import com.helix.app.plan.PlanReviewService
import com.helix.app.plan.StoragePlanReviewPort
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated Room fixtures only. No application container, provider account or production files. */
class ConversationGoalPlanBoundaryDeviceTest {
    @Test fun approvalAndAuditRollbackTogether() =
        Fixture().use { fixture ->
            val plan = fixture.plan()
            val port = StoragePlanReviewPort(fixture.storage, fixture.clock) { error("audit fault") }
            val service = PlanReviewService(port)
            assertThrows(IllegalStateException::class.java) { service.approve(plan.id.value) }
            val row = fixture.storage.plans.resolveEntity(plan.id.value)
            assertEquals("READY", row.state)
            assertNull(row.approvedSnapshot)
            assertTrue(
                fixture.storage.auditEvents
                    .listByCorrelation(plan.id.value)
                    .isEmpty(),
            )
        }

    @Test fun reviseAndCancelAuditFailuresLeaveThePlanReviewable() =
        Fixture().use { fixture ->
            val plan = fixture.plan()
            val port = StoragePlanReviewPort(fixture.storage, fixture.clock) { error("audit fault") }
            val service = PlanReviewService(port)
            assertThrows(IllegalStateException::class.java) { service.revise(plan.id.value) }
            assertEquals(
                "READY",
                fixture.storage.plans
                    .resolveEntity(plan.id.value)
                    .state,
            )
            assertThrows(IllegalStateException::class.java) { service.cancel(plan.id.value) }
            assertEquals(
                "READY",
                fixture.storage.plans
                    .resolveEntity(plan.id.value)
                    .state,
            )
            assertTrue(
                fixture.storage.auditEvents
                    .listByCorrelation(plan.id.value)
                    .isEmpty(),
            )
        }

    @Test fun adapterRejectsStaleExecutionBindingsWithoutCreatingGoals() =
        Fixture().use { fixture ->
            val plan = fixture.plan()
            val port = StoragePlanReviewPort(fixture.storage, fixture.clock, fixture::id)
            PlanReviewService(port).approve(plan.id.value)
            val stale = PlanExecutionBinding(plan.id, plan.version, plan.withNextVersion().sha256())
            assertThrows(IllegalArgumentException::class.java) { port.beginExecution(stale, GoalBudgets(5)) }
            assertEquals(
                "APPROVED",
                fixture.storage.plans
                    .resolveEntity(plan.id.value)
                    .state,
            )
            assertEquals(0, fixture.storage.goals.countByPlan(plan.id.value))
        }

    @Test fun concurrentApprovedExecutionReturnsOneBoundGoalAndOneAudit() =
        Fixture().use { fixture ->
            val plan = fixture.plan()
            val service = PlanReviewService(StoragePlanReviewPort(fixture.storage, fixture.clock, fixture::id))
            val binding = service.approve(plan.id.value)
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            fixture.workersStopped = false
            try {
                val results =
                    (1..2).map {
                        pool.submit(
                            Callable {
                                check(gate.await(10, TimeUnit.SECONDS))
                                service.execute(binding, GoalBudgets(5))
                            },
                        )
                    }
                gate.countDown()
                val ids = results.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(ids[0], ids[1])
                assertEquals(1, fixture.storage.goals.countByPlan(plan.id.value))
                val executionAudits = fixture.storage.auditEvents.listByCorrelation(plan.id.value)
                assertEquals(1, executionAudits.count { it.type == "plan.execution_started" })
            } finally {
                pool.shutdownNow()
                fixture.workersStopped = pool.awaitTermination(10, TimeUnit.SECONDS)
                check(fixture.workersStopped) { "Fixture workers did not stop; private fixture retained" }
            }
        }

    @Test fun invalidAndStaleObjectiveEditsDoNotClaimAnUnboundGoal() =
        Fixture().use { fixture ->
            runBlocking {
                val goal = fixture.goal()
                val actions = fixture.goals()
                assertFalse(actions.editGoalObjective("session", goal, 0, " "))
                assertNull(fixture.storage.goalControls.find(goal))
                assertFalse(actions.editGoalObjective("session", goal, 7, "changed"))
                assertNull(fixture.storage.goalControls.find(goal))
                assertEquals(
                    "original",
                    fixture.storage.goals
                        .resolve(goal)
                        .objective,
                )
            }
        }

    @Test fun objectiveEditCommitsOwnershipRevisionAndAuditTogether() =
        Fixture().use { fixture ->
            runBlocking {
                val goal = fixture.goal()
                val actions = fixture.goals()
                assertTrue(actions.editGoalObjective("session", goal, 0, "changed"))
                assertEquals(
                    "changed",
                    fixture.storage.goals
                        .resolve(goal)
                        .objective,
                )
                assertEquals(
                    "session",
                    fixture.storage.goalControls
                        .find(goal)
                        ?.sessionId,
                )
                assertEquals(
                    1L,
                    fixture.storage.goalControls
                        .find(goal)
                        ?.revision,
                )
                assertFalse(actions.editGoalObjective("session", goal, 0, "stale"))
                assertFalse(actions.editGoalObjective("other-session", goal, 1, "foreign"))
                assertEquals(
                    "changed",
                    fixture.storage.goals
                        .resolve(goal)
                        .objective,
                )
                val correlationId =
                    fixture.storage.goals
                        .resolve(goal)
                        .correlationId
                val editAudits = fixture.storage.auditEvents.listByCorrelation(correlationId)
                assertEquals(1, editAudits.count { it.type == "goal.objective_updated" })
            }
        }

    @Test fun objectiveAuditFailureRollsBackTheEditAndNewOwnership() =
        Fixture().use { fixture ->
            val goal = fixture.goal()
            val actions = fixture.goals { error("audit fault") }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { actions.editGoalObjective("session", goal, 0, "changed") }
            }
            assertNull(fixture.storage.goalControls.find(goal))
            assertEquals(
                "original",
                fixture.storage.goals
                    .resolve(goal)
                    .objective,
            )
        }

    private class Fixture : AutoCloseable {
        var workersStopped = true
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "conversation-goal-plan-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2000)
            }

        init {
            storage.sessions.create("session", "Fixture", null, null, 1000)
            storage.sessions.create("other-session", "Other", null, null, 1000)
        }

        fun id(): String = UUID.randomUUID().toString()

        fun goal(): String = RoomGoalRunCommands(storage, clock, ::id).create("original", emptyList(), GoalBudgets(5))

        fun goals(ids: () -> String = ::id) = RoomConversationGoals(storage, clock, ids, { _, _ -> true }, Any(), {})

        fun plan(): PlanArtifact =
            PlanArtifact(
                PlanId(id()),
                "Isolated Plan review",
                emptyList(),
                listOf(PlanStep("Inspect", "Verify invariants")),
                listOf("one bound Goal"),
                emptyList(),
                1,
            ).also { storage.plans.save(it, "READY", null) }

        override fun close() {
            // A failed termination check must not close or delete resources still used by a worker.
            if (!workersStopped) return
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
