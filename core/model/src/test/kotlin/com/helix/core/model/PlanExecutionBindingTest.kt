package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlanExecutionBinding] (research doc section 4.3; HX2-05): the approval
 * proof an execution may reference — planId + approved version + its SHA-256.
 */
class PlanExecutionBindingTest {
    private val artifact =
        PlanArtifact(
            id = PlanId("plan-1"),
            objective = "Do the thing",
            assumptions = emptyList(),
            steps = listOf(PlanStep("Step one", "description")),
            acceptanceCriteria = listOf("it works"),
            risks = emptyList(),
            version = 1,
        )

    @Test
    fun aBindingCarriesExactlyTheApprovedVersionFacts() {
        val binding = PlanExecutionBinding(artifact.id, artifact.version, artifact.sha256())
        assertEquals(PlanId("plan-1"), binding.planId)
        assertEquals(1, binding.planVersion)
        assertEquals(artifact.sha256(), binding.planHash)
    }

    @Test
    fun aBindingRequiresAVersionOfAtLeastOne() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanExecutionBinding(PlanId("plan-1"), 0, artifact.sha256())
        }
    }

    @Test
    fun aRevisedPlanInvalidatesABindingMadeToThePreviousVersion() {
        val binding = PlanExecutionBinding(artifact.id, artifact.version, artifact.sha256())
        val revised = artifact.withNextVersion()
        // The hash is over the canonical form INCLUDING the version (doc 4.3): the stale
        // binding's hash can no longer match the plan row, so an execution with it must be
        // refused — this is the invariant PlanReviewService.execute enforces.
        assertTrue(binding.planHash != revised.sha256())
    }
}
