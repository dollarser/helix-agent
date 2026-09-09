package com.helix.app.goal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalState
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalCriterionEditorDeviceTest {
    @Test
    fun reviewPersistsOnlySelectionWithoutRunOrBudgetChanges() =
        fixture { storage, editor, selection ->
            val before = storage.goals.resolve("goal").toRuntimeGoal()
            val run = storage.goalRuns.resolve("run")
            editor.stageReview("goal", "c1", selection)
            val after = storage.goals.resolve("goal").toRuntimeGoal()
            assertEquals(before.copy(criteria = after.criteria), after)
            assertEquals(run, storage.goalRuns.resolve("run"))
            assertEquals(1, storage.goalRuns.listByGoal("goal").size)
            assertNotNull(after.criteria.single().pendingReview)
            assertFalse(after.criteria.single().isSatisfied)
            assertNull(after.criteria.single().evidence)
        }

    @Test
    fun bindingEditClearsSelectionAndRejectsStaleEditorState() =
        fixture { storage, editor, selection ->
            editor.stageReview("goal", "c1", selection)
            val before =
                storage.goals
                    .resolve("goal")
                    .toRuntimeGoal()
                    .criteria
                    .single()
            editor.bind("goal", before, "Changed condition", before.binding)
            val after =
                storage.goals
                    .resolve("goal")
                    .toRuntimeGoal()
                    .criteria
                    .single()
            assertNull(after.pendingReview)
            assertNull(after.evidence)
            assertThrows(IllegalArgumentException::class.java) {
                editor.bind("goal", before, "Stale edit", before.binding)
            }
            assertEquals(
                after,
                storage.goals
                    .resolve("goal")
                    .toRuntimeGoal()
                    .criteria
                    .single(),
            )
        }

    @Test
    fun changedReviewSourceRollsBackAndClearDoesNotContinue() =
        fixture { storage, editor, selection ->
            val before = storage.goals.resolve("goal")
            assertThrows(IllegalArgumentException::class.java) {
                editor.stageReview("goal", "c1", selection.copy(sourceHash = Sha256("0".repeat(64))))
            }
            assertEquals(before, storage.goals.resolve("goal"))
            editor.stageReview("goal", "c1", selection)
            editor.clearReview("goal", "c1")
            assertEquals(before, storage.goals.resolve("goal"))
        }

    @Test
    fun activeGoalRejectsBindingAndReviewChanges() =
        fixture(park = false) { storage, editor, selection ->
            val before = storage.goals.resolve("goal")
            val criterion = before.toRuntimeGoal().criteria.single()
            assertThrows(IllegalArgumentException::class.java) {
                editor.bind("goal", criterion, "Changed", criterion.binding)
            }
            assertThrows(IllegalArgumentException::class.java) { editor.stageReview("goal", "c1", selection) }
            assertEquals(before, storage.goals.resolve("goal"))
        }

    @Test
    fun pendingReviewDoesNotBypassExhaustedBudget() =
        fixture { storage, editor, selection ->
            val goal = storage.goals.resolve("goal").toRuntimeGoal()
            storage.goals.updateGoal(goal.copy(modelCalls = goal.budgets.maxModelCalls).toStoredGoal())
            editor.stageReview("goal", "c1", selection)
            val after = storage.goals.resolve("goal").toRuntimeGoal()
            assertTrue(GoalReducer.reduce(after, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
            assertNotNull(after.criteria.single().pendingReview)
            assertFalse(after.criteria.single().isSatisfied)
        }

    private fun fixture(
        park: Boolean = true,
        block: (HelixStorage, GoalCriterionEditor, GoalEvidenceReview) -> Unit,
    ) = withGoalEvidenceStorage { storage ->
        seedGoalToolEvidence(storage)
        val goal = storage.goals.resolve("goal").toRuntimeGoal()
        val criterion =
            goal.criteria.single().withBinding(
                "Check result",
                CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
            )
        if (park) storage.goalRuns.finish(storage.goalRuns.resolve("run"), "RUN_FINISHED", 2000, 0, 0, 0, 0)
        storage.goals.updateGoal(
            goal
                .copy(
                    criteria = listOf(criterion),
                    state = if (park) GoalState.PAUSED else GoalState.RUNNING,
                ).toStoredGoal(),
        )
        val reader = goalEvidenceReader(storage)
        val source = reader.read("goal", "call")
        val selection =
            GoalEvidenceReview(
                "call",
                requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
                source.hash,
            )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(context.cacheDir, "goal-review-${UUID.randomUUID()}")
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(3000)
            }
        val verifier = GoalCriterionVerifier(reader, GoalToolArtifactStore(storage, workspace, reader), clock)
        try {
            block(storage, GoalCriterionEditor(storage, verifier, clock) { UUID.randomUUID().toString() }, selection)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
