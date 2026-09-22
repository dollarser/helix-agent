package com.helix.app.agent

import com.helix.app.provider.ProviderContextSettings
import com.helix.core.agent.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextCapacityTest {
    @Test fun inputOutputAndMessageLimitsAreDistinctAndDoNotOverflow() {
        assertNull(ContextCapacity.failure(512, 900, 100, 900, 1000))
        assertEquals("CONTEXT_MESSAGE_LIMIT", ContextCapacity.failure(513, 900, 100, 900, 1000))
        assertEquals("INPUT_TOKEN_LIMIT", ContextCapacity.failure(512, 901, 100, 900, 1000))
        assertEquals("CONTEXT_WINDOW_LIMIT", ContextCapacity.failure(512, 900, 101, 900, 1000))
        assertEquals(
            "CONTEXT_WINDOW_LIMIT",
            ContextCapacity.failure(1, Long.MAX_VALUE, 1, Long.MAX_VALUE, Long.MAX_VALUE),
        )
    }

    @Test fun summaryAdmissionAccountsForMeasuredScaleAndKeepsSoftFallbackPossible() {
        val request =
            com.helix.core.model.ModelRequest(
                "fixture",
                listOf(
                    com.helix.core.model
                        .ModelMessage(com.helix.core.model.ModelRole.USER, "a".repeat(800)),
                ),
                maxOutputTokens = 256,
            )
        val budgets =
            com.helix.core.model
                .TurnBudgets(10, 10, 10000, 256, 20000)
        val estimate = ModelInputEstimate.of(request).total
        assertNull(ContextCapacity.forSummary(request, estimate, estimate, budgets, 1024))
        assertEquals(
            "CONTEXT_WINDOW_LIMIT",
            ContextCapacity.forSummary(request, estimate * 10, estimate, budgets, 1024),
        )
        assertNull(ContextCapacity.withoutSummary(false, "CONTEXT_SEGMENT_LIMIT", null))
        assertEquals("CONTEXT_SEGMENT_LIMIT", ContextCapacity.withoutSummary(true, "CONTEXT_SEGMENT_LIMIT", null))
    }

    @Test fun utf8CountingMatchesJvmIncludingUnpairedSurrogates() {
        val samples =
            listOf("", "ascii", "\u4e2d\u6587", "\uD83D\uDE00", "\uD800", "\uDC00", "\uD800x\uDC00") +
                (0..65535).chunked(257).map { codes -> codes.map(Int::toChar).joinToString("") }
        samples.forEach { assertEquals(it.toByteArray(Charsets.UTF_8).size.toLong(), TokenEstimator.utf8Bytes(it)) }
    }

    @Test fun fallbackAndManualWindowsAreNotReportedAsProviderFacts() {
        assertEquals("fallback", ProviderContextSettings().windowSource)
        assertEquals("manual", ProviderContextSettings(manualWindow = 8192, serverWindow = 32000).windowSource)
        assertEquals("provider", ProviderContextSettings(manualWindow = 32000, serverWindow = 8192).windowSource)
    }
}
