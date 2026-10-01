package com.helix.app.chat

import com.helix.core.agent.ToolLoopProgress
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.tools.framework.JobObservation
import com.helix.tools.framework.JobObservationBinding
import com.helix.tools.framework.JobObservationEvidence
import com.helix.tools.framework.TimeNowTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JobProgressIntegrationTest {
    private val binding = JobObservationBinding("s", "t", "job", "p", "e", "g", "a".repeat(64))
    private val calls =
        (1..6).map {
            ToolCallEntity("c$it", "t", "c$it", "jobs.await", "1", "{}", "a".repeat(64), "COMPLETED")
        }

    private fun evidence(
        id: String,
        reason: String,
        terminal: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("reason", reason)
            put("completeSet", true)
            val value =
                JobObservation(
                    binding,
                    if (terminal) "SUCCEEDED" else "RUNNING",
                    terminal,
                    false,
                    true,
                    "r",
                    id.drop(1).toLong(),
                )
            put(
                "observations",
                JsonArray(
                    listOf(
                        JsonObject(
                            JobObservationEvidence.encode(value) +
                                ("stale" to JsonPrimitive(false)),
                        ),
                    ),
                ),
            )
        }

    private fun decision(
        reason: String,
        terminal: Boolean = false,
    ) = DurableToolLoopProgress.fromHistory(
        calls,
        { id -> ToolResultEntity("r$id", id, "SUCCEEDED", "same", "changes-every-poll-$id", true) },
        { call -> evidence(call.id, reason, terminal) },
    ) { _, _ -> TimeNowTool.descriptor() }

    @Test fun validWaitingWarnsWithoutMistakingTimestampForTaskProgressOrCompletion() {
        assertEquals(ToolLoopProgress.Decision.WARN, decision("WAIT_EXPIRED"))
    }

    @Test fun repeatedUnreachableSourceStillStopsEvenWithChangingTimestamps() {
        assertEquals(ToolLoopProgress.Decision.STOP, decision("SOURCE_UNAVAILABLE"))
    }

    @Test fun repeatedTerminalQueryIsNotUnlimitedLiveObservation() {
        assertEquals(ToolLoopProgress.Decision.STOP, decision("CONDITION_MET", true))
    }

    @Test fun renamedOrdinaryToolHasNoTrustedWaitingPrivilege() {
        val result =
            DurableToolLoopProgress.fromHistory(
                calls,
                { id ->
                    ToolResultEntity("r$id", id, "SUCCEEDED", "same", null, true)
                },
            ) { _, _ -> TimeNowTool.descriptor() }
        assertEquals(ToolLoopProgress.Decision.STOP, result)
    }

    @Test fun contextProjectionOmitsRevokedAndMalformedSnapshots() {
        val facts = JobObservationEvidence.encode(JobObservation(binding, "RUNNING", false, false, true, "r", 1))
        assertNotNull(JobObservationContext.candidate("ref", facts) { true })
        assertNull(JobObservationContext.candidate("ref", facts) { false })
        assertNull(JobObservationContext.candidate("ref", buildJsonObject {}) { error("invalid data must not pass") })
    }

    @Test fun contextProjectionStripsUnexpectedExternalFields() {
        val facts = JobObservationEvidence.encode(JobObservation(binding, "RUNNING", false, false, true, "r", 1))
        val polluted = JsonObject(facts + ("command" to JsonPrimitive("untrusted instruction")))
        val clean = requireNotNull(JobObservationContext.candidate("ref", polluted) { true })
        assertEquals(false, "command" in clean.facts)
    }
}
