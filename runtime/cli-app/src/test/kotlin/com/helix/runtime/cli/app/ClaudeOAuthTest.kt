package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeOAuthTest {
    @Test fun attemptUsesEphemeralLoopbackPkceAndExactScope() {
        val first = ClaudeOAuthProtocol.createAttempt(32123)
        val second = ClaudeOAuthProtocol.createAttempt(32123)
        assertTrue(first.authorizeUrl.startsWith("https://claude.ai/oauth/authorize?"))
        assertTrue(first.authorizeUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A32123%2Fcallback"))
        assertTrue(first.authorizeUrl.contains("code_challenge_method=S256"))
        assertTrue(first.authorizeUrl.contains("user%3Asessions%3Aclaude_code"))
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
    }

    @Test fun callbackRejectsWrongStateErrorsOversizeAndWrongPath() {
        assertEquals(
            CodexCallbackResult.Code("code"),
            ClaudeOAuthProtocol.parseCallback("/callback?code=code&state=right", "right"),
        )
        assertEquals(
            CodexCallbackResult.Ignored,
            ClaudeOAuthProtocol.parseCallback("/callback?code=code&state=wrong", "right"),
        )
        assertEquals(
            CodexCallbackResult.Ignored,
            ClaudeOAuthProtocol.parseCallback("/other?code=code&state=right", "right"),
        )
        assertTrue(
            ClaudeOAuthProtocol.parseCallback(
                "/callback?error=access_denied&state=right",
                "right",
            ) is CodexCallbackResult.Rejected,
        )
        assertTrue(
            ClaudeOAuthProtocol.parseCallback(
                "/callback?x=${"a".repeat(9000)}",
                "right",
            ) is CodexCallbackResult.Rejected,
        )
    }

    @Test fun tokenAndProfileDecodersAreStrictAndIgnoreProfilePii() {
        val session =
            ClaudeOAuthProtocol.decodeSession(
                """{"access_token":"access","refresh_token":"refresh","expires_in":3600}""".encodeToByteArray(),
                1_000,
            )
        assertEquals(3_601_000, session.expiresAtEpochMillis)
        assertEquals(null, session.accountId)
        assertEquals(
            "pro",
            ClaudeOAuthProtocol.decodeSubscriptionType(
                """{"emailAddress":"private@example.test","account":{"subscription_type":"pro"}}""".encodeToByteArray(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeOAuthProtocol.decodeSession("""{"access_token":"access","expires_in":1}""".encodeToByteArray(), 1)
        }
    }

    @Test fun eligibilityAllowsKnownClaudeCodePlansAndRejectsFreeOrUnknown() {
        listOf("pro", "MAX", "team", "enterprise").forEach { assertTrue(ClaudeOAuthProtocol.isClaudeCodeEligible(it)) }
        listOf("free", "api", "", null).forEach { assertFalse(ClaudeOAuthProtocol.isClaudeCodeEligible(it)) }
    }

    @Test fun controllerPersistsOnlyEligibleSessions() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        val session = CliSubscriptionSession("access", "refresh", null, 10_000)
        val eligible = ClaudeLoginController(vault, transport(ClaudeExchangeResult(session, "pro")))
        assertEquals("pro", eligible.complete(ClaudeOAuthProtocol.createAttempt(12345), "code"))
        assertEquals(session, vault.load(CliSubscriptionProvider.CLAUDE))

        val free = ClaudeLoginController(vault, transport(ClaudeExchangeResult(session, "free")))
        assertThrows(ClaudeEligibilityException::class.java) {
            free.complete(ClaudeOAuthProtocol.createAttempt(12345), "code")
        }
        assertFalse(vault.contains(CliSubscriptionProvider.CLAUDE))
    }

    @Test fun refreshRotatesAndPermanentFailureDeletesWhileTransientPreserves() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        val initial = CliSubscriptionSession("one", "refresh-one", null, 10_000)
        val rotated = CliSubscriptionSession("two", "refresh-two", null, 20_000)
        vault.save(CliSubscriptionProvider.CLAUDE, initial)
        ClaudeLoginController(vault, transport(ClaudeExchangeResult(initial, "pro"), rotated)).refresh()
        assertEquals(rotated, vault.load(CliSubscriptionProvider.CLAUDE))

        val transient = ClaudeLoginController(vault, failingTransport(ClaudeOAuthEndpointException(503, null)))
        assertThrows(ClaudeOAuthEndpointException::class.java) { transient.refresh() }
        assertTrue(vault.contains(CliSubscriptionProvider.CLAUDE))

        val permanent =
            ClaudeLoginController(vault, failingTransport(ClaudeOAuthEndpointException(400, "invalid_grant")))
        assertThrows(ClaudeOAuthEndpointException::class.java) { permanent.refresh() }
        assertFalse(vault.contains(CliSubscriptionProvider.CLAUDE))
    }

    private fun transport(
        exchange: ClaudeExchangeResult,
        refreshed: CliSubscriptionSession = exchange.session,
    ) = object : ClaudeOAuthTransport {
        override fun exchange(
            attempt: ClaudeOAuthAttempt,
            code: String,
        ) = exchange

        override fun refresh(session: CliSubscriptionSession) = refreshed
    }

    private fun failingTransport(error: RuntimeException) =
        object : ClaudeOAuthTransport {
            override fun exchange(
                attempt: ClaudeOAuthAttempt,
                code: String,
            ): ClaudeExchangeResult = throw error

            override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession = throw error
        }

    private class MemoryStore : CliSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun get(name: String) = values.getValue(name)

        override fun delete(name: String) {
            values.remove(name)
        }

        override fun contains(name: String) = values.containsKey(name)
    }
}
