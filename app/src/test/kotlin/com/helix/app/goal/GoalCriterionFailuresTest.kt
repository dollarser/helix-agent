package com.helix.app.goal

import com.helix.core.storage.entity.AuditEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCriterionFailuresTest {
    @Test fun latestFailureSurvivesUntilExplicitRebindingOrReview() {
        val invalid = event("goal.criterion_invalidated", "CONTENT_CHANGED")
        assertEquals(mapOf("c1" to GoalEvidenceFailure.CONTENT_CHANGED), GoalCriterionFailures.from(listOf(invalid)))
        val edits = listOf("goal.criterion_bound", "goal.criterion_review_staged", "goal.criterion_review_cleared")
        edits.forEach { type ->
            assertTrue(GoalCriterionFailures.from(listOf(invalid, event(type))).isEmpty())
        }
        assertEquals(
            mapOf("c1" to GoalEvidenceFailure.TOO_LARGE),
            GoalCriterionFailures.from(listOf(invalid, event("goal.criterion_invalidated", "TOO_LARGE"))),
        )
    }

    @Test fun unknownAndMalformedDiagnosticsCannotProduceAuthority() {
        val unknown = event("goal.criterion_invalidated", "EVIDENCE_INVALID")
        assertEquals(mapOf("c1" to GoalEvidenceFailure.UNKNOWN), GoalCriterionFailures.from(listOf(unknown)))
        val malformed = unknown.copy(redactedPayload = "not json")
        assertTrue(GoalCriterionFailures.from(listOf(malformed, event("model.completed", "CONTENT_CHANGED"))).isEmpty())
    }

    private fun event(
        type: String,
        reason: String = "",
    ) = AuditEventEntity(
        "id",
        "goal",
        type,
        "SYSTEM",
        """{"criterionId":"c1","reason":"$reason"}""",
        1000,
    )
}
