package com.helix.app.goal

import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [planStepsSection] (research doc 5.1): a plan-executing goal's prompt carries
 * the approved plan's steps, and the "grants no permissions" boundary is part of the CONTENT.
 * The function is pure — storage resolution lives in the caller — so no storage stack is needed.
 */
class PlanStepsSectionTest {
    private val plan =
        PlanArtifact(
            id = PlanId("plan-1"),
            objective = "Migrate the storage layer",
            assumptions = emptyList(),
            steps = listOf(PlanStep("Read the spec", "study it"), PlanStep("Write the migration", "code it")),
            acceptanceCriteria = listOf("rows survive a schema migration"),
            risks = emptyList(),
            version = 3,
        )

    @Test
    fun rendersTheApprovedPlanStepsInOrderWithThePermissionBoundary() {
        val expected =
            "Approved plan v3: you are executing this plan.\n" +
                "1. Read the spec\n" +
                "   study it\n" +
                "2. Write the migration\n" +
                "   code it\n" +
                "\n" +
                "The plan guides the work but grants no permissions: every write, deletion or egress " +
                "still goes through normal authorization."
        assertEquals(expected, planStepsSection(plan))
    }

    @Test
    fun reflectsTheApprovedVersion() {
        assertTrue(planStepsSection(plan.copy(version = 7)).startsWith("Approved plan v7:"))
        assertTrue(planStepsSection(plan).startsWith("Approved plan v3:"))
    }

    @Test
    fun doesNotLeakTheObjectiveOrCriteriaIntoTheStepsSection() {
        // The section is the STEPS only; the objective and acceptance criteria are the
        // goal.objective section's concern, not this one.
        val section = planStepsSection(plan)
        assertFalse(section.contains("Objective:"))
        assertFalse(section.contains("rows survive a schema migration"))
    }
}
