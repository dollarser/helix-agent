package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.tools.framework.BuiltInToolSource
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalEvidenceContinueDeviceTest {
    @Test
    fun verifiedContinueCompletesWithoutNewExecutionOrModelRows() =
        fixture { storage, service ->
            assertTrue(service.tryComplete("goal", "session"))
            val completed = storage.goals.resolve("goal")
            assertEquals("COMPLETED", completed.state)
            assertEquals(2, completed.runCount)
            assertNull(completed.criteria.single().pendingReview)
            assertEquals(1, storage.turns.listBySession("session").size)
            assertEquals(1, storage.toolCalls.listByTurn("turn").size)
            assertTrue(storage.modelCalls.listByTurn("turn").isEmpty())
            val verificationRun = storage.goalRuns.listByGoal("goal").single { it.id != "run" }
            assertEquals("COMPLETED", verificationRun.outcome)
            assertEquals(0, verificationRun.modelCalls)
            assertEquals(0, verificationRun.toolCalls)
            assertEquals(0L, verificationRun.tokens)
            assertTrue(service.tryComplete("goal", "session"))
            assertEquals(completed, storage.goals.resolve("goal"))
            assertEquals(2, storage.goalRuns.listByGoal("goal").size)
        }

    @Test
    fun exhaustedBudgetBlocksEvenFullyReviewedEvidence() =
        fixture { storage, service ->
            val goal = storage.goals.resolve("goal").toRuntimeGoal()
            storage.goals.updateGoal(goal.copy(modelCalls = goal.budgets.maxModelCalls).toStoredGoal())
            val before = storage.goals.resolve("goal")
            assertThrows(IllegalArgumentException::class.java) { service.tryComplete("goal", "session") }
            assertEquals(before, storage.goals.resolve("goal"))
            assertEquals(1, storage.goalRuns.listByGoal("goal").size)
        }

    @Test
    fun missingSourceInvalidatesSelectionWithoutCreatingRun() =
        fixture { storage, service ->
            val result = requireNotNull(storage.toolResults.byToolCall("call"))
            storage.contentStore.delete(ContentRef.parse(requireNotNull(result.contentRef)))
            assertFalse(service.tryComplete("goal", "session"))
            val after = storage.goals.resolve("goal")
            assertEquals("PAUSED", after.state)
            assertNull(after.criteria.single().pendingReview)
            val events = storage.auditEvents.listByCorrelation(after.correlationId)
            assertEquals(GoalEvidenceFailure.CONTENT_CHANGED, GoalCriterionFailures.from(events)["c1"])
            assertEquals(1, storage.goalRuns.listByGoal("goal").size)
        }

    @Test
    fun otherSessionAndUnsettledCallsAreRejected() =
        fixture { storage, service ->
            assertThrows(IllegalArgumentException::class.java) { service.tryComplete("goal", "other") }
            storage.toolCalls.append("pending", "turn", "pending", "time.now", "1", "{}", "PENDING")
            assertThrows(IllegalArgumentException::class.java) { service.tryComplete("goal", "session") }
            assertEquals("PAUSED", storage.goals.resolve("goal").state)
        }

    @Test
    fun outerFailureRollsBackReviewConsumptionAndRunCreation() =
        fixture { storage, service ->
            val before = storage.goals.resolve("goal")
            assertThrows(IllegalStateException::class.java) {
                storage.withTransaction {
                    assertTrue(service.tryComplete("goal", "session"))
                    error("injected commit failure")
                }
            }
            assertEquals(before, storage.goals.resolve("goal"))
            assertEquals(1, storage.goalRuns.listByGoal("goal").size)
        }

    private fun fixture(block: (HelixStorage, GoalEvidenceContinue) -> Unit) =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(3000)
                }
            val goal = storage.goals.resolve("goal").toRuntimeGoal()
            val criterion =
                goal.criteria.single().withBinding(
                    "Review result",
                    CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
                )
            storage.goalRuns.finish(storage.goalRuns.resolve("run"), "RUN_FINISHED", 2000, 0, 0, 0, 0)
            storage.goals.updateGoal(goal.copy(state = GoalState.PAUSED, criteria = listOf(criterion)).toStoredGoal())
            val context = ApplicationProvider.getApplicationContext<Context>()
            val workspace = File(context.cacheDir, "goal-evidence-continue-${UUID.randomUUID()}")
            val registry = ToolRegistry(listOf(BuiltInToolSource(listOf(TimeNowTool.descriptor()))))
            val reader = GoalToolEvidenceReader(storage, registry)
            val verifier = GoalCriterionVerifier(reader, GoalToolArtifactStore(storage, workspace, reader), clock)
            val review =
                GoalEvidenceReview(
                    "call",
                    requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
                    reader.read("goal", "call").hash,
                )
            val editor = GoalCriterionEditor(storage, verifier, clock) { UUID.randomUUID().toString() }
            editor.stageReview("goal", "c1", review)
            val completion =
                GoalCompletionVerifier(storage, registry, workspace, clock) {
                    UUID.randomUUID().toString()
                }
            try {
                block(storage, GoalEvidenceContinue(storage, completion, clock) { UUID.randomUUID().toString() })
            } finally {
                workspace.deleteRecursively()
            }
        }
}
