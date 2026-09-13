package com.helix.app.plan

import com.helix.core.agent.ModeDecision
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.PlanId
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.entity.PlanStepEntity
import com.helix.core.storage.repository.PlanRepository
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for the `plan.submit` built-in tool (research doc section 4.3; HX2-05): the
 * contract facts, the executor's persist-REVIEW_REQUIRED behavior and its fail-closed exits,
 * over a hand-written [PlanDao] fake — no dispatcher, no Room.
 */
class PlanToolsTest {
    private val fakeDao = FakePlanDao()
    private val plans = PlanRepository(fakeDao)
    private var nextId = 0
    private val idGenerator: () -> String = { "plan-${++nextId}" }

    private fun args(
        steps: List<JsonObject> =
            listOf(
                step("Read the spec"),
                step("Write the migration"),
            ),
        objective: String = "Migrate the storage layer",
        acceptanceCriteria: List<JsonPrimitive> = listOf(JsonPrimitive("rows survive a schema migration")),
        assumptions: List<JsonPrimitive> = emptyList(),
        risks: List<JsonPrimitive> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("objective", JsonPrimitive(objective))
            put("steps", JsonArray(steps))
            put("acceptanceCriteria", JsonArray(acceptanceCriteria))
            if (assumptions.isNotEmpty()) {
                put("assumptions", JsonArray(assumptions))
            }
            if (risks.isNotEmpty()) {
                put("risks", JsonArray(risks))
            }
        }

    private fun step(
        title: String,
        description: String = "do $title",
    ) = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
    }

    private fun call(
        args: JsonObject,
        cancel: CancelSignal = NoCancellation,
    ) = ExecutableToolCall(
        toolCallId = "tc-1",
        toolName = PlanTools.NAME,
        toolVersion = "1",
        args = args,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        deadline = Instant.now().plusSeconds(30),
        cancel = cancel,
        sessionId = "s1",
        turnId = "t1",
    )

    // --- the contract ---

    @Test
    fun theContractIsAMetadataL0ControlToolAdmittedByPlanMode() {
        val d = PlanTools.descriptor() // construction also enforces the ToolSchema subset
        assertEquals(ToolName("plan.submit"), d.name)
        assertEquals(ToolVersion(1), d.version)
        assertEquals(ToolOperationClass.METADATA, d.operationClass)
        assertEquals(RiskLevel.L0, d.baseRisk)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        // Plan mode admits READ_ONLY or METADATA at dynamic risk <= L1 (core:agent ModePolicy):
        // the structured termination tool is a distinct METADATA op, not a disguised READ_ONLY.
        assertTrue(
            ModePolicy.evaluate(
                AgentMode.PLAN,
                ToolModeProfile(ToolOperationClass.METADATA, RiskLevel.L0),
            ) is ModeDecision.Allowed,
        )
    }

    // --- the executor: persist REVIEW_REQUIRED, return the binding facts ---

    @Test
    fun aSuccessfulSubmitPersistsTheArtifactReviewRequiredAndReturnsItsFacts() {
        val executor = PlanTools.executor(plans, idGenerator)
        val result = executor.execute(call(args(assumptions = listOf(JsonPrimitive("single writer")))))

        val completed =
            result as? ToolExecutorResult.Completed
                ?: throw AssertionError("expected Completed, got $result")
        val saved = fakeDao.saved ?: throw AssertionError("no plan row was written")
        assertEquals("READY", saved.state)
        assertNull(saved.evidenceRef)
        assertEquals("plan-1", saved.id)
        assertEquals(2, fakeDao.savedSteps.size)
        assertEquals(0, fakeDao.savedSteps[0].sequence)
        assertEquals("Read the spec", fakeDao.savedSteps[0].title)

        val output = completed.output as JsonObject
        assertEquals(saved.id, (output["planId"] as JsonPrimitive).content)
        assertEquals(1L, (output["version"] as JsonPrimitive).content.toLong())
        assertEquals(saved.hash, (output["hash"] as JsonPrimitive).content)
        assertEquals("READY", (output["state"] as JsonPrimitive).content)
    }

    @Test
    fun aPlanThatViolatesTheDomainBoundsFailsSideEffectFreeWithoutWritingARow() {
        val executor = PlanTools.executor(plans, idGenerator)
        val result = executor.execute(call(args(steps = emptyList())))

        val failed =
            result as? ToolExecutorResult.Failed
                ?: throw AssertionError("expected Failed, got $result")
        assertTrue(failed.sideEffectFree)
        assertNull(fakeDao.saved)
    }

    @Test
    fun aMissingRequiredFieldFailsSideEffectFree() {
        val executor = PlanTools.executor(plans, idGenerator)
        val noCriteria =
            buildJsonObject {
                put("objective", JsonPrimitive("o"))
                put("steps", JsonArray(listOf(step("s"))))
            }
        val result = executor.execute(call(noCriteria))
        val failed =
            result as? ToolExecutorResult.Failed
                ?: throw AssertionError("expected Failed, got $result")
        assertTrue(failed.sideEffectFree)
        assertNull(fakeDao.saved)
    }

    @Test
    fun aStorageFailureFailsWithTheSafeLabelAndIsNotSideEffectFree() {
        fakeDao.failInsert = true
        val executor = PlanTools.executor(plans, idGenerator)
        val result = executor.execute(call(args()))
        val failed =
            result as? ToolExecutorResult.Failed
                ?: throw AssertionError("expected Failed, got $result")
        assertEquals("the plan could not be stored", failed.detail)
        assertTrue(!failed.sideEffectFree)
    }

    @Test
    fun aCancelledCallIsCancelled() {
        val executor = PlanTools.executor(plans, idGenerator)
        val result =
            executor.execute(
                call(
                    args(),
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled(): Boolean = true
                        },
                ),
            )
        assertTrue(result is ToolExecutorResult.Cancelled)
    }

    private class FakePlanDao : PlanDao {
        var saved: PlanEntity? = null
        var savedSteps: List<PlanStepEntity> = emptyList()
        var failInsert: Boolean = false

        override fun insert(plan: PlanEntity) {
            if (failInsert) {
                check(false) { "simulated storage failure" }
            }
            saved = plan
        }

        override fun insertSteps(steps: List<PlanStepEntity>) {
            savedSteps = steps
        }

        override fun byId(id: String): PlanEntity? = saved?.takeIf { it.id == id }

        override fun stepsOf(planId: String): List<PlanStepEntity> =
            saved?.takeIf { it.id == planId }?.let { savedSteps } ?: emptyList()

        override fun list(): List<PlanEntity> = listOfNotNull(saved)

        override fun updateState(
            id: String,
            state: String,
            evidenceRef: String?,
        ) {
            saved = saved?.copy(state = state, evidenceRef = evidenceRef)
        }

        override fun delete(id: String): Int = if (saved?.id == id) 1 else 0
    }
}
