package com.helix.app.goal

import com.helix.core.agent.Criterion
import com.helix.core.agent.CriterionEvidence
import com.helix.core.agent.Goal
import com.helix.core.model.ArtifactRef
import com.helix.core.model.CorrelationId
import com.helix.core.model.CriterionEvidenceSource
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.CriterionVerificationRecord
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.GoalRunId
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.TurnId
import com.helix.core.storage.criteria.CriteriaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GoalCriterionMappingTest {
    @Test
    fun bindingAndReceiptSurviveDomainStorageJsonRoundTrip() {
        val binding = CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, "")
        val evidence =
            CriterionEvidence(
                CriterionEvidence.HOST_VERIFIER,
                ArtifactRef("artifact-1"),
                null,
                CriterionVerificationRecord(
                    binding.method,
                    binding.hash("c1", "review output"),
                    CriterionEvidenceSource(GoalId("goal-1"), GoalRunId("run-1"), SessionId("s1"), TurnId("t1")),
                    Sha256("a".repeat(64)),
                    1000,
                ),
            )
        val goal = goal(Criterion("c1", "review output", evidence, binding))
        assertEquals(goal, roundTrip(goal))
    }

    @Test
    fun oldBareReferencesRemainHistoricalAndUnverified() {
        val old = CriterionEvidence("old verifier", ArtifactRef("artifact-1"), null)
        val restored = roundTrip(goal(Criterion("c1", "old condition", old))).criteria.single()
        assertEquals(old, restored.evidence)
        assertNull(restored.binding)
        assertFalse(restored.isSatisfied)
    }

    private fun roundTrip(goal: Goal): Goal {
        val stored = goal.toStoredGoal()
        return stored.copy(criteria = CriteriaCodec.decode(CriteriaCodec.encode(stored.criteria))).toRuntimeGoal()
    }

    private fun goal(criterion: Criterion): Goal =
        Goal.initial(
            GoalId("goal-1"),
            "Test output",
            listOf(criterion),
            GoalBudgets(2, 5, 1000, 10000, 10000, 1),
            CorrelationId("corr-1"),
        )
}
