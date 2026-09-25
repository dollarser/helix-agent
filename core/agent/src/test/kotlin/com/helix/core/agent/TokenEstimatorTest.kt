package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        assertThrows(IllegalArgumentException::class.java) { TokenEstimator.estimateTokens(-1) }
    }
}
