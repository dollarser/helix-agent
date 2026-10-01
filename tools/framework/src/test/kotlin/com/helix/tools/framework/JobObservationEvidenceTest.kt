package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobObservationEvidenceTest {
    private val value =
        JobObservation(
            JobObservationBinding("s", "t", "c", "p", "e", "g", "a".repeat(64)),
            "RUNNING",
            false,
            false,
            true,
            "r",
            10,
        )

    private fun payload(
        observation: JobObservation = value,
        stale: Boolean = false,
        reason: String = "WAIT_EXPIRED",
    ) = buildJsonObject {
        put("reason", reason)
        put("completeSet", true)
        put(
            "observations",
            JsonArray(
                listOf(
                    JsonObject(
                        JobObservationEvidence.encode(observation) +
                            ("stale" to JsonPrimitive(stale)),
                    ),
                ),
            ),
        )
    }

    @Test fun encodingRetainsOriginalIdentityWithoutOutputBodies() {
        assertEquals(value, JobObservationEvidence.decode(JobObservationEvidence.encode(value)))
    }

    @Test fun timestampChangesNeverBecomeTaskProgress() {
        assertEquals(
            JobObservationEvidence.fingerprint(payload()),
            JobObservationEvidence.fingerprint(payload(value.copy(observedAtMillis = 999))),
        )
    }

    @Test fun freshRunningWaitIsNotCompletion() {
        assertTrue(JobObservationEvidence.waiting(payload()))
        assertFalse(JobObservationEvidence.waiting(payload(value.copy(terminal = true))))
        assertFalse(JobObservationEvidence.waiting(payload(value.copy(requiresReview = true))))
    }

    @Test fun staleUnknownOrPartialEvidenceDoesNotGetWaitExemption() {
        assertFalse(JobObservationEvidence.waiting(payload(stale = true)))
        assertFalse(JobObservationEvidence.waiting(payload(reason = "SOURCE_UNAVAILABLE")))
        assertFalse(JobObservationEvidence.waiting(JsonObject(payload() + ("completeSet" to JsonPrimitive(false)))))
    }

    @Test fun malformedEvidenceIsRejectedRatherThanFabricatingRunning() {
        assertThrows(IllegalArgumentException::class.java) {
            JobObservationEvidence.decode(
                JsonObject(JobObservationEvidence.encode(value) + ("state" to JsonPrimitive("bad state"))),
            )
        }
    }
}
