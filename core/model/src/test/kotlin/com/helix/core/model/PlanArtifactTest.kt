package com.helix.core.model

import com.helix.core.model.internal.JsonNode
import com.helix.core.model.internal.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanArtifactTest {
    private fun artifact(
        objective: String = "Investigate the login flow",
        steps: List<PlanStep> = listOf(PlanStep("Read the flow", "Trace the login call path")),
        version: Int = 1,
    ): PlanArtifact =
        PlanArtifact(
            id = PlanId("plan-1"),
            objective = objective,
            assumptions = listOf("Assumes Android 14"),
            steps = steps,
            acceptanceCriteria = listOf("Login succeeds without errors"),
            risks = emptyList(),
            version = version,
        )

    @Test
    fun storageStringMatchesKnownVector() {
        val expected =
            "{\"id\":\"plan-1\",\"objective\":\"Investigate the login flow\"," +
                "\"assumptions\":[\"Assumes Android 14\"]," +
                "\"steps\":[{\"title\":\"Read the flow\",\"description\":\"Trace the login call path\"}]," +
                "\"acceptanceCriteria\":[\"Login succeeds without errors\"]," +
                "\"risks\":[],\"version\":1}"
        assertEquals(expected, artifact().toStorageString())
    }

    @Test
    fun storageStringIsStrictCanonicalJson() {
        val node = parseJson(artifact().toStorageString())
        assertTrue(node is JsonNode.Obj)
        assertEquals(7, (node as JsonNode.Obj).entries.size)
        assertEquals("id", node.entries.first().first)
        assertEquals("version", node.entries.last().first)
    }

    @Test
    fun hashIsDeterministicAndContentSensitive() {
        val plan = artifact()
        val hash = plan.sha256()
        assertEquals(64, hash.hex.length)
        assertEquals(hash, plan.sha256())
        assertTrue(plan.copy(objective = "A different objective").sha256() != hash)
        assertTrue(plan.copy(version = 2).sha256() != hash)

        val twoSteps =
            artifact(
                steps = listOf(PlanStep("One", "Do one"), PlanStep("Two", "Do two")),
            )
        val reordered = twoSteps.copy(steps = twoSteps.steps.reversed())
        assertTrue(reordered.sha256() != twoSteps.sha256())
    }

    @Test
    fun nextVersionKeepsContentButChangesHash() {
        val plan = artifact()
        val next = plan.withNextVersion()
        assertEquals(2, next.version)
        assertEquals(plan.id, next.id)
        assertEquals(plan.steps, next.steps)
        assertTrue(next.sha256() != plan.sha256())
    }

    @Test
    fun specialCharactersAreEscaped() {
        val plan = artifact(objective = "He said \"hi\"\nline two")
        val objectivePart = "{\"id\":\"plan-1\",\"objective\":\"He said \\\"hi\\\"\\nline two\","
        val restPart =
            "\"assumptions\":[\"Assumes Android 14\"]," +
                "\"steps\":[{\"title\":\"Read the flow\",\"description\":\"Trace the login call path\"}]," +
                "\"acceptanceCriteria\":[\"Login succeeds without errors\"],\"risks\":[],\"version\":1}"
        val expected = objectivePart + restPart
        assertEquals(expected, plan.toStorageString())
        assertTrue(parseJson(plan.toStorageString()) is JsonNode.Obj)
    }

    @Test
    fun rejectsInvalidArtifacts() {
        val valid = artifact()
        assertThrows<IllegalArgumentException> { valid.copy(steps = emptyList()) }
        assertThrows<IllegalArgumentException> { valid.copy(acceptanceCriteria = emptyList()) }
        assertThrows<IllegalArgumentException> { artifact(objective = "   ") }
        assertThrows<IllegalArgumentException> { artifact(version = 0) }
        assertThrows<IllegalArgumentException> { artifact(objective = "x".repeat(1025)) }
        assertThrows<IllegalArgumentException> {
            valid.copy(steps = valid.steps + PlanStep("t".repeat(257), "d"))
        }
        assertThrows<IllegalArgumentException> {
            valid.copy(acceptanceCriteria = valid.acceptanceCriteria + "x".repeat(1025))
        }
        assertThrows<IllegalArgumentException> {
            valid.copy(assumptions = List(33) { "assumption $it" })
        }
    }
}
