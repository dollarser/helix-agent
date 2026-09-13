package com.helix.app.agent

import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The redaction contract of [promptSnapshotRecord] (research doc section 4.4): the record
 * carries per-section provenance — name, order, scope, source, trust, contentHash — plus the
 * request identity and the assembly fingerprint, and NEVER the section content itself, so the
 * `model_calls` row and the `prompt.assembled` audit event persist which exact prompt bytes a
 * request used without persisting the prompt bodies.
 */
class PromptSnapshotRecordTest {
    @Test
    fun anEmptyAssemblyRecordsNothing() {
        assertNull(promptSnapshotRecord(PromptRegistry().resolveAndAssemble(), "mc-1", "t-1"))
    }

    @Test
    fun theRecordCarriesProvenancePerSectionAndNeverTheContent() {
        val base = "Harness identity text with a SECRET-BASE marker."
        val goal = "Goal marker text GOAL-SECRET"
        val snapshot = snapshot(base, goal)
        val record = requireNotNull(promptSnapshotRecord(snapshot, "mc-1", "turn-7"))

        assertEquals("mc-1", record.modelCallId)
        assertEquals("turn-7", record.turnId)
        assertEquals(snapshot.fingerprint, record.fingerprint)

        val sections = Json.parseToJsonElement(record.sectionsJson).jsonArray
        assertEquals(2, sections.size)
        val first = sections[0].jsonObject
        assertEquals("env.base", first["name"]!!.jsonPrimitive.content)
        assertEquals(-2_000, first["order"]!!.jsonPrimitive.content.toInt())
        assertEquals("RUNTIME", first["scope"]!!.jsonPrimitive.content)
        assertEquals("BUILTIN_TEMPLATE", first["source"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", first["trust"]!!.jsonPrimitive.content)
        assertEquals(sha256Hex(base), first["contentHash"]!!.jsonPrimitive.content)
        val second = sections[1].jsonObject
        assertEquals("goal.identity", second["name"]!!.jsonPrimitive.content)
        assertEquals("USER_REQUEST", second["source"]!!.jsonPrimitive.content)
        assertEquals("USER", second["trust"]!!.jsonPrimitive.content)
        assertEquals(sha256Hex(goal), second["contentHash"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAuditPayloadMirrorsTheRecordAndNeverTheContent() {
        val base = "Harness identity text with a SECRET-BASE marker."
        val record =
            requireNotNull(
                promptSnapshotRecord(
                    snapshot(base, "Goal marker text GOAL-SECRET"),
                    "mc-9",
                    "turn-3",
                ),
            )
        val payload = Json.parseToJsonElement(record.auditPayload).jsonObject
        assertEquals("mc-9", payload["modelCallId"]!!.jsonPrimitive.content)
        assertEquals("turn-3", payload["turnId"]!!.jsonPrimitive.content)
        assertEquals(record.fingerprint, payload["fingerprint"]!!.jsonPrimitive.content)
        assertEquals(2, payload["sections"]!!.jsonArray.size)

        // The redaction invariant: the content bytes never leave the request path.
        assertTrue("SECRET-BASE" !in record.sectionsJson)
        assertTrue("SECRET-BASE" !in record.auditPayload)
        assertTrue("GOAL-SECRET" !in record.sectionsJson)
        assertTrue("GOAL-SECRET" !in record.auditPayload)
    }

    private fun snapshot(
        base: String,
        goal: String,
    ): com.helix.core.agent.PromptSnapshot {
        val registry =
            PromptRegistry()
                .register(
                    PromptSection("env.base", -2_000, PromptScope.RUNTIME, PromptSource.BUILTIN_TEMPLATE) {
                        base
                    },
                )
        if (goal.isNotEmpty()) {
            registry.register(
                PromptSection("goal.identity", -1_000, PromptScope.GOAL, PromptSource.USER_REQUEST) {
                    goal
                },
            )
        }
        return registry.resolveAndAssemble()
    }

    private fun sha256Hex(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
