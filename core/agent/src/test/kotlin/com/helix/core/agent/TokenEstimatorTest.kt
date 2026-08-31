package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun estimatesRoundUpToWholeTokens() {
        assertEquals(0, TokenEstimator.estimateTokens(0))
        assertEquals(1, TokenEstimator.estimateTokens(1))
        assertEquals(1, TokenEstimator.estimateTokens(4))
        assertEquals(2, TokenEstimator.estimateTokens(5))
        assertEquals(250, TokenEstimator.estimateTokens(1000))
    }

    @Test
    fun rejectsNegativeByteCount() {
        assertThrows<IllegalArgumentException> { TokenEstimator.estimateTokens(-1) }
    }
}

class CallTokenAccountTest {
    @Test
    fun reportedTotalIsTrustedOverDerivedSum() {
        val account =
            CallTokenAccount(Fixtures.call(1), 1000, inputTokens = 100, outputTokens = 50, totalTokens = 90)
        assertEquals(100, account.effectiveInput)
        assertEquals(50, account.effectiveOutput)
        assertEquals(90, account.effectiveTotal)
    }

    @Test
    fun derivedTotalSumsKnownAndEstimatedParts() {
        // input known (100), output missing -> estimated from response bytes (400 -> 100).
        val account =
            CallTokenAccount(Fixtures.call(1), 1000, inputTokens = 100, outputTokens = null, responseBytes = 400)
        assertEquals(200, account.effectiveTotal)
    }

    @Test
    fun missingUsageNeverCountsAsZero() {
        val account = CallTokenAccount(Fixtures.call(1), 1000, responseBytes = 400)
        assertEquals(250, account.effectiveInput)
        assertEquals(100, account.effectiveOutput)
        assertEquals(350, account.effectiveTotal)
    }

    @Test
    fun rejectsNegativeValues() {
        assertThrows<IllegalArgumentException> { CallTokenAccount(Fixtures.call(1), -1) }
        assertThrows<IllegalArgumentException> { CallTokenAccount(Fixtures.call(1), 10, inputTokens = -1) }
    }
}
