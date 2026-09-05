package com.helix.runtime.cli.app

import okhttp3.Dns
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64

class CodexOAuthTest {
    @Test fun boundedDnsCacheReusesSuccessfulLookupDuringLogin() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        var calls = 0
        val upstream =
            Dns {
                calls += 1
                if (calls > 1) throw UnknownHostException(it)
                expected
            }
        val cache = BoundedDnsCache(upstream)

        assertEquals(expected, cache.lookup("auth.openai.com"))
        assertEquals(expected, cache.lookup("auth.openai.com"))
        assertEquals(1, calls)
    }

    @Test fun boundedResponseReaderAcceptsShortResponseAndRejectsOverflow() {
        assertEquals("short", Buffer().writeUtf8("short").readBoundedByteArray(64).decodeToString())

        assertThrows(IllegalArgumentException::class.java) {
            Buffer().write(ByteArray(65)).readBoundedByteArray(64)
        }
    }

    @Test fun attemptUsesFixedOriginLoopbackStateAndPkce() {
        val first = CodexOAuthProtocol.createAttempt(1455)
        val second = CodexOAuthProtocol.createAttempt(1455)

        assertTrue(first.authorizeUrl.startsWith("https://auth.openai.com/oauth/authorize?"))
        assertTrue(first.authorizeUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        assertTrue(first.authorizeUrl.contains("code_challenge_method=S256"))
        assertTrue(first.authorizeUrl.contains("state="))
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
    }

    @Test fun callbackAcceptsOnlyExactPathStateAndCode() {
        assertEquals(
            CodexCallbackResult.Code("code-one"),
            CodexOAuthProtocol.parseCallback("/auth/callback?code=code-one&state=state-one", "state-one"),
        )
        assertEquals(
            CodexCallbackResult.Ignored,
            CodexOAuthProtocol.parseCallback("/other?code=code-one&state=state-one", "state-one"),
        )
        assertEquals(
            CodexCallbackResult.Ignored,
            CodexOAuthProtocol.parseCallback("/auth/callback?code=code-one&state=wrong", "state-one"),
        )
        assertTrue(
            CodexOAuthProtocol.parseCallback("/auth/callback?error=access_denied&state=state-one", "state-one")
                is CodexCallbackResult.Rejected,
        )
    }

    @Test fun tokenResponseRequiresExpiryRefreshAndAccountId() {
        val idToken = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-1"}}""")
        val response =
            """{"access_token":"access","refresh_token":"refresh","id_token":"$idToken","expires_in":3600}"""
                .encodeToByteArray()
        val session = CodexOAuthProtocol.decodeSession(response, 1_000)

        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals("acct-1", session.accountId)
        assertEquals(3_601_000, session.expiresAtEpochMillis)

        assertThrows(IllegalArgumentException::class.java) {
            CodexOAuthProtocol.decodeSession("""{"access_token":"a","expires_in":1}""".encodeToByteArray(), 1)
        }
    }

    @Test fun controllerStoresRotatedTokensAndLogoutDeletesThem() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        val initial = CliSubscriptionSession("access-1", "refresh-1", "id-1", 10_000, "acct-1")
        val rotated = CliSubscriptionSession("access-2", "refresh-2", "id-2", 20_000, "acct-1")
        val transport =
            object : CodexOAuthTransport {
                override fun exchange(
                    attempt: CodexOAuthAttempt,
                    code: String,
                ) = initial

                override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession {
                    assertEquals(initial, session)
                    return rotated
                }
            }
        val controller = CodexLoginController(vault, transport)
        controller.complete(CodexOAuthProtocol.createAttempt(1455), "code")
        controller.refresh()
        assertEquals(rotated, vault.load(CliSubscriptionProvider.CODEX))

        controller.logout()
        assertFalse(vault.contains(CliSubscriptionProvider.CODEX))
    }

    @Test fun permanentRefreshFailureDeletesSessionButTransientFailurePreservesIt() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        val session = CliSubscriptionSession("access", "refresh", "id", 10_000, "acct")
        vault.save(CliSubscriptionProvider.CODEX, session)
        val transient = CodexLoginController(vault, failingTransport(CodexOAuthEndpointException(503, null)))
        assertThrows(CodexOAuthEndpointException::class.java) { transient.refresh() }
        assertEquals(session, vault.load(CliSubscriptionProvider.CODEX))

        val permanent = CodexLoginController(vault, failingTransport(CodexOAuthEndpointException(400, "invalid_grant")))
        assertThrows(CodexOAuthEndpointException::class.java) { permanent.refresh() }
        assertFalse(vault.contains(CliSubscriptionProvider.CODEX))
    }

    private fun failingTransport(error: RuntimeException) =
        object : CodexOAuthTransport {
            override fun exchange(
                attempt: CodexOAuthAttempt,
                code: String,
            ): CliSubscriptionSession = throw error

            override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession = throw error
        }

    private fun jwt(payload: String): String =
        "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.encodeToByteArray())}.signature"

    private class MemoryStore : CliSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun get(name: String): String = values.getValue(name)

        override fun delete(name: String) {
            values.remove(name)
        }

        override fun contains(name: String): Boolean = values.containsKey(name)
    }
}
