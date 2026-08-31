package com.helix.core.storage.mapping

import com.helix.core.model.ArtifactRef
import com.helix.core.model.CorrelationId
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.HelixError
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import com.helix.core.storage.assertThrows
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.criteria.StoredEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EntityMappingTest {
    private fun plan(): PlanArtifact =
        PlanArtifact(
            id = PlanId("plan-abc123"),
            objective = "Build the storage layer",
            assumptions = listOf("single device only"),
            steps =
                listOf(
                    PlanStep("Schema", "Write the entities and DAOs"),
                    PlanStep("Repository", "Wrap the DAOs with validation"),
                ),
            acceptanceCriteria = listOf("unit tests pass", "migration fixture passes"),
            risks = listOf("schema drift"),
            version = 1,
        )

    @Test
    fun `plan round trips through normalized columns and step rows`() {
        val artifact = plan()
        val entity = EntityMappers.planEntityFor(artifact, "DRAFT", null)
        assertEquals(artifact.id.value, entity.id)
        assertEquals(artifact.sha256().hex, entity.hash)
        assertEquals(1, entity.version)
        val steps = EntityMappers.planStepsFor(artifact)
        assertEquals(2, steps.size)
        assertEquals(0, steps[0].sequence)
        val recovered = entity.toPlanArtifact(steps)
        assertEquals(artifact, recovered)
    }

    @Test
    fun `plan hash mismatch is detected on recovery`() {
        val entity = EntityMappers.planEntityFor(plan(), "DRAFT", null)
        val tampered = entity.copy(objective = "Something else entirely")
        val steps = EntityMappers.planStepsFor(plan())
        assertThrows("hash mismatch") { tampered.toPlanArtifact(steps) }
    }

    @Test
    fun `plan steps out of order are rejected`() {
        val entity = EntityMappers.planEntityFor(plan(), "DRAFT", null)
        val steps = EntityMappers.planStepsFor(plan()).map { it.copy(sequence = 9 - it.sequence) }
        assertThrows("steps out of order") { entity.toPlanArtifact(steps) }
    }

    @Test
    fun `string list json escapes and round trips`() {
        val items = listOf("plain", "with \"quotes\"", "back\\slash", "line\nbreak")
        val json = EntityMappers.stringListJson(items)
        assertEquals(items, EntityMappers.parseStringListJson(json))
        assertThrows("number rejected") { EntityMappers.parseStringListJson("[1]") }
        assertEquals(emptyList<String>(), EntityMappers.parseStringListJson("[]"))
    }

    private fun goal(): StoredGoal =
        StoredGoal(
            id = "goal-xyz789",
            objective = "Ship M1 storage",
            criteria =
                listOf(
                    StoredCriterion("c1", "Schema export", null),
                    StoredCriterion(
                        "c2",
                        "Migration fixture",
                        StoredEvidence(
                            verifier = "verifier.tests",
                            artifactRef = ArtifactRef("artifact-9"),
                            toolCallId = ToolCallId("tc-77"),
                        ),
                    ),
                ),
            budgets = GoalBudgets(10, 20, 100_000L, 600_000L, 120_000L, 3),
            state = GoalState.READY.name,
            planId = "plan-abc123",
            planHash = "f".repeat(64),
            nextCheckpoint = 1_700_000_000_000L,
            correlationId = "corr-12345678",
            runCount = 2,
            modelCalls = 5,
            toolCalls = 9,
            totalTokens = 12_345L,
            runTimeMillis = 43_210L,
            currentWakeMillis = 0L,
            retries = 1,
            lastWakeReason = "USER_OPEN",
            error = null,
            finishReason = null,
        )

    @Test
    fun `goal round trips through the entity with budgets criteria and error`() {
        val stored = goal()
        val entity = stored.toGoalEntity()
        assertEquals(stored.id, entity.id)
        assertEquals(GoalBudgets(10, 20, 100_000L, 600_000L, 120_000L, 3).toStorageString(), entity.budgets)
        val recovered = entity.toStoredGoal()
        assertEquals(stored, recovered)

        val withError =
            stored.copy(
                state = GoalState.FAILED.name,
                error =
                    HelixError(
                        code = ErrorCode.STORAGE,
                        userMessage = "The database is unavailable.",
                        retryable = false,
                        safeDetails = mapOf("table" to "goals"),
                        correlationId = CorrelationId("corr-12345678"),
                    ),
            )
        val recoveredWithError = withError.toGoalEntity().toStoredGoal()
        assertEquals(withError, recoveredWithError)
        assertFalse(recoveredWithError.error?.retryable ?: true)
    }

    @Test
    fun `plan id and hash must be set together`() {
        assertThrows("planId without planHash") {
            goal().copy(planId = "plan-abc123", planHash = null)
        }
        assertNull(goal().copy(planId = null, planHash = null).planId)
    }

    @Test
    fun `enumByName resolves and rejects state names`() {
        assertEquals(TurnState.CREATED, turnStateName("CREATED"))
        assertThrows("unknown state") { turnStateName("NOPE") }
        assertThrows("blank state") { turnStateName("") }
    }
}
