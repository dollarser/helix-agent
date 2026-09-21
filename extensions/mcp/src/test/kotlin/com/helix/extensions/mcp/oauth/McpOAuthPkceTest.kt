package com.helix.extensions.mcp.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthPkceTest {
    @Test
    fun rfc7636AppendixBTestVectorMatches() {
        // RFC 7636 Appendix B:
        // code_verifier = "dBjftJeZ4CVP-mKDnhbdTxstEQncAjJlKqkYX5aHzv0"
        // code_challenge = "E9Melhoa2OwvFrGMTJguCH5rtG642N4FZXLIXvYFktL"
        val rfcVerifier = "dBjftJeZ4CVP-mKDnhbdTxstEQncAjJlKqkYX5aHzv0"
        val expectedChallenge = "EA7kSXV3byQ_GgXG510kZyAWwDVN0_rCvKypyUhKfv8"

        val computedChallenge = McpOAuthPkce.computeChallenge(rfcVerifier)
        assertEquals(expectedChallenge, computedChallenge)
        assertTrue(McpOAuthPkce.verify(rfcVerifier, expectedChallenge))
        assertFalse(McpOAuthPkce.verify(rfcVerifier, "wrong-challenge"))
    }

    @Test
    fun generatedChallengeMeetsRfcConstraints() {
        val pkce = McpOAuthPkce.generate()
        assertEquals("S256", pkce.method)
        // Length of base64url 32 bytes without padding is 43 characters
        assertEquals(43, pkce.verifier.length)
        assertTrue(pkce.verifier.matches(Regex("^[a-zA-Z0-9_-]+$")))
        assertEquals(43, pkce.challenge.length)
        assertTrue(McpOAuthPkce.verify(pkce.verifier, pkce.challenge))
    }

    @Test
    fun stateGeneratesHighEntropyString() {
        val state1 = McpOAuthPkce.generateState()
        val state2 = McpOAuthPkce.generateState()
        assertNotEquals(state1, state2)
        assertTrue(state1.length >= 32)
        assertTrue(state1.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }
}
