package com.helix.core.agent

import com.helix.core.model.ModelEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelClientObservationTest {
    @Test fun separatesFirstResponseFromAcceptedAnswerAndPreservesReportedUsage() {
        var now = 0L
        val timer = ModelRequestObservation { now }
        now = 10_000_000
        timer.event(ModelEvent.ReasoningDelta("private"), false)
        now = 20_000_000
        timer.event(ModelEvent.TextDelta("ignored"), false)
        now = 30_000_000
        timer.event(ModelEvent.TextDelta("answer"), true)
        now = 50_000_000
        val observed = timer.finish(false)
        assertEquals(ModelClientObservation(50, 10, 30, false), observed)
        assertEquals(observed, ModelClientObservation.parse(observed.json()))
        val state = ModelStreamState()
        state.recordClientObservation(observed)
        val usage = Json.parseToJsonElement(requireNotNull(state.usageJson)) as JsonObject
        assertFalse(usage.containsKey("inputTokens"))
        assertFalse(usage.containsKey("outputTokens"))
        assertNull(state.inputTokens)
        assertNull(state.outputTokens)
        state.apply(ModelEvent.Usage(17, 9))
        val reported = Json.parseToJsonElement(requireNotNull(state.usageJson)) as JsonObject
        assertEquals("17", reported.getValue("inputTokens").toString())
        assertEquals("9", reported.getValue("outputTokens").toString())
        assertEquals(observed.json(), reported.getValue("clientObservation"))
        assertThrows(IllegalStateException::class.java) { state.recordClientObservation(observed) }
    }

    @Test fun absentEventsDoNotInventFirstTextOrAcceptMalformedTiming() {
        var now = 0L
        val timer = ModelRequestObservation { now }
        now = 100_000_000
        assertEquals(ModelClientObservation(100, null, null, true), timer.finish(true))
        listOf(
            "{\"durationMs\":-1,\"summary\":false}",
            "{\"durationMs\":10,\"firstTextMs\":11,\"summary\":false}",
            "{\"durationMs\":\"10\",\"summary\":false}",
            "{\"durationMs\":10,\"summary\":\"false\"}",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                ModelClientObservation.parse(Json.parseToJsonElement(raw) as JsonObject)
            }
        }
    }
}
