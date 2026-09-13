package com.helix.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatContextUsageTest {
    @Test fun missingOrInvalidTelemetryIsNotAnEmptyContext() {
        assertNull(ChatContextUsage().fraction)
        assertNull(ChatContextUsage(100, null).percentage)
        assertNull(ChatContextUsage(-1, 100).fraction)
        assertNull(ChatContextUsage(100, 0).fraction)
    }

    @Test fun overflowIsVisibleWhileRingRemainsBounded() {
        assertEquals(120L, ChatContextUsage(120, 100).percentage)
        assertEquals(1f, ChatContextUsage(120, 100).fraction)
        assertEquals(0f, ChatContextUsage(0, 100).fraction)
        assertEquals(85L, ChatContextUsage(8500, 10000).percentage)
    }

    @Test fun onlyMatchingModelAndEndpointCanSupplyUsage() {
        val snapshot = """{"endpoint":"https://example.test/v1","model":"a"}"""
        val usage = """{"inputTokens":85,"outputTokens":10}"""
        assertEquals(85L, ChatContextProjection.inputFor(snapshot, usage, "https://example.test/v1", "a"))
        assertNull(ChatContextProjection.inputFor(snapshot, usage, "https://other.test", "a"))
        assertNull(ChatContextProjection.inputFor(snapshot, usage, "https://example.test/v1", "b"))
        assertNull(ChatContextProjection.inputFor(snapshot, null, "https://example.test/v1", "a"))
        assertNull(ChatContextProjection.inputFor(snapshot, "{}", "https://example.test/v1", "a"))
        assertNull(ChatContextProjection.inputFor("broken", usage, "https://example.test/v1", "a"))
        assertNull(ChatContextProjection.inputFor(snapshot, """{"inputTokens":-1}""", "https://example.test/v1", "a"))
    }
}
