package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CliSubscriptionCredentialVaultTest {
    private val store = MemorySecretStore()
    private val vault = CliSubscriptionCredentialVault(store)

    @Test fun savesLoadsAndOverwritesOneProviderWithoutExposingTokensInState() {
        vault.save(CliSubscriptionProvider.CODEX, session("access-one", "refresh-one", 100))
        vault.save(CliSubscriptionProvider.CODEX, session("access-two", "refresh-two", 200))

        assertEquals(session("access-two", "refresh-two", 200), vault.load(CliSubscriptionProvider.CODEX))
        assertEquals(
            mapOf(
                "codex" to "LOGGED_IN",
                "claude" to "LOGGED_OUT",
                "grok" to "LOGGED_OUT",
                "copilot" to "LOGGED_OUT",
            ),
            vault.publicStates(),
        )
        assertFalse(vault.publicStates().toString().contains("access-two"))
        assertFalse(vault.publicStates().toString().contains("refresh-two"))
    }

    @Test fun logoutIsIdempotentAndProviderScoped() {
        vault.save(CliSubscriptionProvider.CODEX, session("codex-a", "codex-r", 100))
        vault.save(CliSubscriptionProvider.CLAUDE, session("claude-a", "claude-r", 100))

        vault.logout(CliSubscriptionProvider.CODEX)
        vault.logout(CliSubscriptionProvider.CODEX)

        assertFalse(vault.contains(CliSubscriptionProvider.CODEX))
        assertTrue(vault.contains(CliSubscriptionProvider.CLAUDE))
    }

    @Test fun malformedOrUnknownCredentialSchemaFailsClosed() {
        store.put("subscription-codex", "{}")
        assertThrows(Exception::class.java) { vault.load(CliSubscriptionProvider.CODEX) }

        store.put(
            "subscription-codex",
            """{"version":1,"accessToken":"a","refreshToken":"r","expiresAtEpochMillis":1,"extra":true}""",
        )
        assertThrows(IllegalArgumentException::class.java) { vault.load(CliSubscriptionProvider.CODEX) }
    }

    @Test fun blankTokensAndInvalidExpiryAreRejectedBeforeStorage() {
        assertThrows(IllegalArgumentException::class.java) { session("", "refresh", 1) }
        assertThrows(IllegalArgumentException::class.java) { session("access", "", 1) }
        assertThrows(IllegalArgumentException::class.java) { session("access", "refresh", 0) }
        assertTrue(store.aliases().isEmpty())
    }

    @Test fun providerSetIsClosedAndOversizedCredentialIsRejectedBeforeStorage() {
        assertEquals(
            setOf("codex", "claude", "grok", "copilot"),
            CliSubscriptionProvider.entries.map { it.wireId }.toSet(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            vault.save(CliSubscriptionProvider.CODEX, session("a".repeat(17 * 1024), "refresh", 1))
        }
        assertTrue(store.aliases().isEmpty())
    }

    private fun session(
        access: String,
        refresh: String,
        expiry: Long,
    ) = CliSubscriptionSession(access, refresh, null, expiry)

    private class MemorySecretStore : CliSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun get(name: String): String = requireNotNull(values[name])

        override fun delete(name: String) {
            values.remove(name)
        }

        override fun contains(name: String): Boolean = values.containsKey(name)

        fun aliases(): Set<String> = values.keys
    }
}
