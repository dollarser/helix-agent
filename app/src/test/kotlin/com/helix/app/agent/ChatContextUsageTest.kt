package com.helix.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatContextUsageTest {
    @Test fun pendingFailedAndDiscardedSummaryRequestsPreserveLastConfirmedInput() {
        val snapshot = """{"transportIdentity":"endpoint","model":"a"}"""
        val previous = ChatContextProjection.InputSample(snapshot, """{"inputTokens":7474}""", true, false)
        for (latest in listOf(
            ChatContextProjection.InputSample(snapshot, null, false, false),
            ChatContextProjection.InputSample(snapshot, """{"inputTokens":872}""", false, true),
            ChatContextProjection.InputSample(snapshot, """{"inputTokens":872}""", true, true),
        )) {
            val result = ChatContextProjection.select(sequenceOf(latest, previous), "endpoint", "a")
            assertEquals(7474L, result.inputTokens)
            assertEquals(false, result.estimatedAfterCompaction)
        }
    }

    @Test fun onlyCommittedSummaryChangesContextAndNextOrdinaryReplyReplacesEstimate() {
        val snapshot = """{"transportIdentity":"endpoint","model":"a"}"""
        val compacted = ChatContextProjection.InputSample(snapshot, """{"inputTokens":872}""", true, true, 2500)
        val result = ChatContextProjection.select(sequenceOf(compacted), "endpoint", "a")
        assertEquals(2500L, result.inputTokens)
        assertEquals(true, result.estimatedAfterCompaction)
        val next = ChatContextProjection.InputSample(snapshot, """{"inputTokens":3000}""", true, false)
        assertEquals(3000L, ChatContextProjection.select(sequenceOf(next, compacted), "endpoint", "a").inputTokens)
        assertNull(ChatContextProjection.select(sequenceOf(next, compacted), "endpoint", "other").inputTokens)
    }

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

    @Test fun percentageLabelsRoundDownIncludingSmallNonzeroUsage() {
        assertEquals("0%", ChatContextUsage(500, 262144).percentageLabel)
        assertEquals("1%", ChatContextUsage(199, 10000).percentageLabel)
        assertEquals("0%", ChatContextUsage(0, 262144).percentageLabel)
        assertEquals("25%", ChatContextUsage(65536, 262144).percentageLabel)
        assertEquals("?", ChatContextUsage(null, 262144).percentageLabel)
        assertEquals("?", ChatContextUsage(500, null).percentageLabel)
    }

    @Test fun onlyMatchingModelAndEndpointCanSupplyUsage() {
        val snapshot = """{"transportIdentity":"https://example.test/v1","model":"a"}"""
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
