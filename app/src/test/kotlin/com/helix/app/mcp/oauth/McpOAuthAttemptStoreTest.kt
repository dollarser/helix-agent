package com.helix.app.mcp.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class McpOAuthAttemptStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun attemptIsSavedAndConsumedExactlyOnce() {
        val dir = tempFolder.newFolder("attempts")
        val store = McpOAuthAttemptStore(dir, OAuthTestSecrets())

        val state = "test-state-123456"
        val attempt =
            McpOAuthAttempt(
                attemptId = UUID.randomUUID().toString(),
                serverId = "mcp-slack",
                issuer = "https://slack.com",
                tokenEndpoint = "https://slack.com/api/oauth.v2.access",
                clientId = "client-123",
                redirectUri = "helix://oauth/mcp/callback",
                scope = "chat:write",
                state = state,
                codeVerifier = "test-verifier-abcdef",
                createdAtMs = 1000L,
                expiresAtMs = 1000L + McpOAuthAttempt.DEFAULT_TTL_MS,
            )

        store.saveAttempt(attempt)

        // First consume succeeds
        val consumed = store.consumeAttempt(state, nowMs = 2000L)
        assertNotNull(consumed)
        assertEquals(attempt.attemptId, consumed!!.attemptId)
        assertEquals("mcp-slack", consumed.serverId)

        // Second consume must return null (one-time consumption)
        val secondConsume = store.consumeAttempt(state, nowMs = 2000L)
        assertNull(secondConsume)
    }

    @Test
    fun expiredAttemptReturnsNullAndCleanupDeletesExpired() {
        val dir = tempFolder.newFolder("attempts")
        val store = McpOAuthAttemptStore(dir, OAuthTestSecrets())

        val expiredState = "expired-state"
        val validState = "valid-state"

        val expiredAttempt =
            McpOAuthAttempt(
                attemptId = "expired",
                serverId = "s1",
                issuer = "https://auth.example.com",
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "c1",
                redirectUri = "helix://oauth/mcp/callback",
                scope = "read",
                state = expiredState,
                codeVerifier = "verifier-1",
                createdAtMs = 1000L,
                expiresAtMs = 5000L,
            )

        val validAttempt =
            McpOAuthAttempt(
                attemptId = "valid",
                serverId = "s2",
                issuer = "https://auth.example.com",
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "c2",
                redirectUri = "helix://oauth/mcp/callback",
                scope = "read",
                state = validState,
                codeVerifier = "verifier-2",
                createdAtMs = 1000L,
                expiresAtMs = 1000L + McpOAuthAttempt.DEFAULT_TTL_MS,
            )

        store.saveAttempt(expiredAttempt)
        store.saveAttempt(validAttempt)

        // Attempting to consume expired attempt with nowMs > expiresAtMs returns null
        assertNull(store.consumeAttempt(expiredState, nowMs = 6000L))

        // Re-save expired to test cleanupExpired
        store.saveAttempt(expiredAttempt)
        store.cleanupExpired(nowMs = 6000L)

        // Valid attempt still intact
        val validConsumed = store.consumeAttempt(validState, nowMs = 6000L)
        assertNotNull(validConsumed)
        assertEquals("s2", validConsumed!!.serverId)
    }

    @Test
    fun cancelAttemptDeletesFile() {
        val dir = tempFolder.newFolder("attempts")
        val store = McpOAuthAttemptStore(dir, OAuthTestSecrets())

        val state = "state-to-cancel"
        val attempt =
            McpOAuthAttempt(
                attemptId = "cancel-id",
                serverId = "s",
                issuer = "iss",
                tokenEndpoint = "tok",
                clientId = "c",
                redirectUri = "red",
                scope = "scp",
                state = state,
                codeVerifier = "ver",
                createdAtMs = 1000L,
                expiresAtMs = 50000L,
            )

        store.saveAttempt(attempt)
        store.cancelAttempt(state)

        assertNull(store.consumeAttempt(state))
    }
}
