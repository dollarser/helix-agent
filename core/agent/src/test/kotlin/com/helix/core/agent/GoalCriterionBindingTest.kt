package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCriterionBindingTest {
    @Test
    fun legacyBareReferenceCannotSatisfyOrCompleteAGoal() {
        val goal = runningGoal()
        val legacy = CriterionEvidence("legacy", ArtifactRef("artifact-1"), null)
        assertTrue(GoalReducer.reduce(goal, GoalEvent.CriterionSatisfied("c1", legacy)).ignored)
        val oldRow = goal.copy(criteria = listOf(Criterion("c1", "Login works", legacy)))
        assertFalse(oldRow.criteria.single().isSatisfied)
        assertTrue(GoalReducer.reduce(oldRow, GoalEvent.CompleteRequested).ignored)
    }

    @Test
    fun historicalCompletionIsReadableButNotUpgradedOrReopened() {
        val legacy = CriterionEvidence("legacy", ArtifactRef("artifact-1"), null)
        val historical =
            runningGoal().copy(
                criteria = listOf(Criterion("c1", "Login works", legacy)),
                state = GoalState.COMPLETED,
                finishReason = "completed",
            )
        assertEquals(historical, GoalReducer.afterProcessDeath(historical))
        assertTrue(GoalReducer.reduce(historical, GoalEvent.Continued(GoalWakeReason.USER_OPEN)).ignored)
        assertFalse(historical.criteria.single().isSatisfied)
    }

    @Test
    fun anotherGoalReceiptCannotBeIntakenOrCompleted() {
        val goal = runningGoal()
        val good = GoalFixtures.evidence()
        val receipt = requireNotNull(good.verification)
        val foreign = good.copy(verification = receipt.copy(source = receipt.source.copy(goalId = GoalId("other"))))
        assertTrue(GoalReducer.reduce(goal, GoalEvent.CriterionSatisfied("c1", foreign)).ignored)
        val injected = goal.copy(criteria = listOf(goal.criteria.single().copy(evidence = foreign)))
        assertTrue(GoalReducer.reduce(injected, GoalEvent.CompleteRequested).ignored)
        assertEquals(1, injected.unsatisfiedCriteria.size)
    }

    @Test
    fun changedDescriptionOrMethodInvalidatesReceipt() {
        val criterion = GoalFixtures.criterion().withEvidence(GoalFixtures.evidence())
        assertTrue(criterion.isSatisfied)
        assertFalse(criterion.copy(description = "Another condition").isSatisfied)
        assertFalse(criterion.copy(binding = null).isSatisfied)
        assertFalse(criterion.copy(id = "c2").isSatisfied)
    }

    @Test
    fun userEditThenRevertDoesNotResurrectEvidence() {
        val criterion = GoalFixtures.criterion().withEvidence(GoalFixtures.evidence())
        val edited = criterion.withBinding("Changed", criterion.binding)
        val reverted = edited.withBinding(criterion.description, criterion.binding)
        assertNull(edited.evidence)
        assertNull(reverted.evidence)
        assertFalse(reverted.isSatisfied)
    }

    @Test
    fun modelVerifierLabelCannotSubstituteForHostReceipt() {
        val goal = runningGoal()
        val forgedName = GoalFixtures.evidence().copy(verifier = "model-says-verified")
        assertTrue(GoalReducer.reduce(goal, GoalEvent.CriterionSatisfied("c1", forgedName)).ignored)
    }
}
