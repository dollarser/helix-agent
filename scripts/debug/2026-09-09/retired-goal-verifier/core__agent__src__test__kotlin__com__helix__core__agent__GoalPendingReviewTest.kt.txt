package com.helix.core.agent

import com.helix.core.model.CriterionPendingReview
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPendingReviewTest {
    private val criterion = GoalFixtures.criterion()
    private val pending =
        CriterionPendingReview(
            ToolCallId("call"),
            requireNotNull(criterion.binding).hash(criterion.id, criterion.description),
            Sha256("a".repeat(64)),
            null,
            100,
        )

    @Test
    fun pendingReviewCannotCompleteARunningGoal() {
        val staged = criterion.withPendingReview(pending)
        assertFalse(staged.isSatisfied)
        assertTrue(
            GoalReducer.reduce(runningGoal().copy(criteria = listOf(staged)), GoalEvent.CompleteRequested).ignored,
        )
    }

    @Test
    fun bindingEditClearsReviewAndOldEvidence() {
        val edited = criterion.withPendingReview(pending).withBinding("changed", criterion.binding)
        assertNull(edited.pendingReview)
        assertNull(edited.evidence)
    }

    @Test
    fun verifiedEvidenceConsumesPendingSelection() {
        val accepted = criterion.withPendingReview(pending).withEvidence(GoalFixtures.evidence())
        assertTrue(accepted.isSatisfied)
        assertNull(accepted.pendingReview)
    }
}
