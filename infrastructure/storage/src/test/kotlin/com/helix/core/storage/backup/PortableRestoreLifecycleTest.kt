package com.helix.core.storage.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PortableRestoreLifecycleTest {
    private fun row(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun interruptedModelCallSettlesInThePersistedFieldWithoutChangingCompletedHistory() {
        val running = row("""{"state":"RUNNING","createdAt":30,"settledAt":null,"requestId":"original"}""")
        val restored = PortableRestoreLifecycle.apply("model_calls", running, emptySet(), emptySet(), 20)
        assertEquals(running.keys, restored.keys)
        assertEquals("INTERRUPTED", restored.getValue("state").jsonPrimitive.content)
        assertEquals("30", restored.getValue("settledAt").toString())
        assertEquals("original", restored.getValue("requestId").jsonPrimitive.content)
        val completed = row("""{"state":"COMPLETED","createdAt":10,"settledAt":12}""")
        assertEquals(completed, PortableRestoreLifecycle.apply("model_calls", completed, emptySet(), emptySet(), 90))
    }

    @Test fun unfinishedEffectsRequireReviewWhileUnstartedCallsNeverReplay() {
        val running = row("""{"id":"t","state":"RUNNING","startedAt":10,"endedAt":null}""")
        val review = PortableRestoreLifecycle.apply("turns", running, setOf("t"), emptySet(), 20)
        assertEquals("NEEDS_REVIEW", review.getValue("state").jsonPrimitive.content)
        assertEquals("null", review.getValue("endedAt").toString())
        val stopped = PortableRestoreLifecycle.apply("turns", running, emptySet(), emptySet(), 20)
        assertEquals("INTERRUPTED", stopped.getValue("state").jsonPrimitive.content)
        assertEquals("20", stopped.getValue("endedAt").toString())
        listOf("PENDING", "AWAITING_APPROVAL", "RUNNING", "DENIED", "INTERRUPTED").forEach { state ->
            val restored =
                PortableRestoreLifecycle.apply(
                    "tool_calls",
                    row("""{"state":"$state"}"""),
                    emptySet(),
                    emptySet(),
                    20,
                )
            val expected =
                when (state) {
                    "PENDING", "AWAITING_APPROVAL" -> "CANCELLED"
                    "RUNNING" -> "NEEDS_REVIEW"
                    else -> state
                }
            assertEquals(expected, restored.getValue("state").jsonPrimitive.content)
        }
    }

    @Test fun settledHistoryStaysSettledAndLiveAuthorityDoesNotTransfer() {
        val completed = row("""{"id":"t","state":"COMPLETED","startedAt":10,"endedAt":12}""")
        assertEquals(completed, PortableRestoreLifecycle.apply("turns", completed, emptySet(), emptySet(), 90))
        val review = row("""{"toolCallId":"c","decision":"CONFIRMED","reviewedAt":15}""")
        assertEquals(review, PortableRestoreLifecycle.apply("tool_call_reviews", review, emptySet(), emptySet(), 90))
        val goal = row("""{"id":"g","state":"RUNNING","currentWakeMillis":100}""")
        assertEquals(
            "PAUSED",
            PortableRestoreLifecycle
                .apply("goals", goal, emptySet(), emptySet(), 90)
                .getValue("state")
                .jsonPrimitive.content,
        )
        assertEquals(
            "BLOCKED",
            PortableRestoreLifecycle
                .apply("goals", goal, emptySet(), setOf("g"), 90)
                .getValue("state")
                .jsonPrimitive.content,
        )
        val plan = row("""{"state":"APPROVED","approvedSnapshot":"old authority"}""")
        val restored = PortableRestoreLifecycle.apply("plans", plan, emptySet(), emptySet(), 90)
        assertEquals("READY", restored.getValue("state").jsonPrimitive.content)
        assertEquals("null", restored.getValue("approvedSnapshot").toString())
    }
}
