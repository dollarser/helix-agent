package com.helix.app.application.context

import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.core.agent.PromptContentRetention
import com.helix.core.agent.PromptSource
import com.helix.core.model.AgentMode
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MemoryContextSnapshot
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.MemorySummarySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SystemPromptContextTest {
    private val request =
        SystemPromptRequest("session-a", "turn-a", FileScopePath("workspace", ""), AgentMode.ACT, setOf("read"))
    private val mapper = SystemPromptContext(packagedPromptTemplates)

    @Test
    fun missingFactsDoNotInventProjectMemoryOrGoal() {
        val snapshot = mapper.build(request, SystemPromptFacts(request))
        assertTrue(snapshot.sections.any { it.name == "env.base" })
        assertFalse(snapshot.sections.any { it.name.startsWith("goal.") || it.name.startsWith("memory.") })
        assertFalse(snapshot.sections.any { it.name == "project.instructions" || it.name == "workspace.project" })
    }

    @Test
    fun mappingIsRepeatableAndPreservesWorkspaceAndProjectProvenance() {
        val facts =
            SystemPromptFacts(
                request,
                project = ProjectPromptFacts("project-a", 4, "Project", "Description", "Project guidance"),
                projectInstructions = "Workspace guidance",
            )
        val snapshot = mapper.build(request, facts)
        assertEquals(snapshot, mapper.build(request, facts))
        val project = snapshot.sections.single { it.name == "project.instructions" }
        assertEquals(PromptSource.USER_CONFIGURATION, project.source)
        assertTrue(project.content.contains("[PROJECT project-a revision=4]"))
        assertTrue(project.content.contains("does not grant permissions"))
        assertEquals(
            PromptSource.WORKSPACE_INSTRUCTION,
            snapshot.sections.single { it.name == "workspace.project" }.source,
        )
    }

    @Test
    fun factsCannotBeUsedForAnotherSessionTurnDirectoryOrToolSet() {
        val facts = SystemPromptFacts(request)
        listOf(
            request.copy(sessionId = "session-b"),
            request.copy(turnId = "turn-b"),
            request.copy(directory = FileScopePath("other", "")),
            request.copy(toolNames = setOf("memory.read")),
        ).forEach { other ->
            assertThrows(IllegalArgumentException::class.java) { mapper.build(other, facts) }
        }
    }

    @Test
    fun goalFactsMustMatchTheRequestTurn() {
        val wrongGoal = GoalPromptFacts("turn-b", "run-b", "goal-b", "Other objective", emptyList(), null)
        assertThrows(IllegalArgumentException::class.java) {
            mapper.build(request, SystemPromptFacts(request, goal = wrongGoal))
        }
    }

    @Test
    fun memoryRetainsHashTrustBudgetAndOnlyAdmittedReadReference() {
        val memory =
            MemoryContextSnapshot(listOf(MemorySummarySnapshot(MemoryScope.Global, "quoted", "hash", true)))
        for (tools in listOf(emptySet(), setOf("memory.read"))) {
            val admitted = request.copy(toolNames = tools)
            val summary =
                mapper
                    .build(admitted, SystemPromptFacts(admitted, memory = memory))
                    .sections
                    .single { it.name == "memory.summary.global" }
            assertEquals(PromptSource.EXTERNAL_CONTENT, summary.source)
            assertEquals(PromptContentRetention.OPTIONAL, summary.retention)
            assertEquals(50, summary.priority)
            assertEquals("hash", summary.identity?.revision)
            assertEquals("memory.read" in tools, summary.readReference != null)
            assertTrue(summary.content.contains("prefixTruncated=true"))
        }
    }

    @Test
    fun activeGoalUsesSameRegistryWithApprovedPlanAndNoDuplicateWorkspaceSection() {
        val plan =
            PlanArtifact(
                PlanId("plan"),
                "Objective",
                emptyList(),
                listOf(PlanStep("Read", "Inspect")),
                listOf("Verified"),
                emptyList(),
                1,
            )
        val goal = GoalPromptFacts("turn-a", "run-a", "goal-a", "Objective", listOf("Criterion"), plan)
        val snapshot = mapper.build(request, SystemPromptFacts(request, projectInstructions = "Guidance", goal = goal))
        assertFalse(snapshot.sections.any { it.name == "workspace.project" })
        assertEquals("Guidance", snapshot.sections.single { it.name == "goal.project" }.content)
        assertEquals(PromptSource.USER_REQUEST, snapshot.sections.single { it.name == "goal.plan" }.source)
        assertTrue(snapshot.content.contains("grants no permissions"))
        assertTrue(snapshot.content.contains("- Criterion"))
        val withoutPlan = mapper.build(request, SystemPromptFacts(request, goal = goal.copy(plan = null)))
        assertFalse(withoutPlan.sections.any { it.name == "goal.plan" })
        assertTrue(withoutPlan.sections.any { it.name == "goal.objective" })
    }

    @Test
    fun cancellationAndTemplateFailureAreNotConvertedToSuccessfulPartialPrompts() {
        val cancellation = CancellationException("cancelled")
        val cancelled = SystemPromptContext(PromptTemplateSource { throw cancellation })
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) { cancelled.build(request, SystemPromptFacts(request)) },
        )
        val failed = SystemPromptContext(PromptTemplateSource { error("missing template") })
        assertThrows(IllegalStateException::class.java) { failed.build(request, SystemPromptFacts(request)) }
    }
}
