package com.helix.app.mcp.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class McpOAuthBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun attempt(state: String = "valid-state") =
        McpOAuthAttempt(
            "attempt-id",
            "server",
            "https://issuer.example",
            "https://issuer.example/token",
            "client",
            "helix://oauth/mcp/callback",
            "read",
            state,
            "private-verifier",
            100,
            10000,
        )

    @Test fun traversalAndMalformedContentNeverDeleteOtherFiles() {
        val root = temporary.newFolder()
        val directory = File(root, "attempts")
        val victim = File(root, "unrelated.json").apply { writeText("{\"fixture\":true}") }
        val store = McpOAuthAttemptStore(directory, OAuthTestSecrets())
        listOf("../unrelated", "/unrelated", "..", "x/y", "x\\y", "x".repeat(129)).forEach { state ->
            assertNull(store.consumeAttempt(state, 200))
            store.cancelAttempt(state)
        }
        assertTrue(victim.isFile)
        assertEquals("{\"fixture\":true}", victim.readText())
    }

    @Test fun verifierOnlyInSecretsAndSurvivesStoreRecreation() {
        val dir = temporary.newFolder()
        val secrets = OAuthTestSecrets()
        McpOAuthAttemptStore(dir, secrets).saveAttempt(attempt())
        assertFalse(File(dir, "valid-state.json").readText().contains("private-verifier"))
        assertFalse(File(dir, "valid-state.json").readText().contains("codeVerifier"))
        assertEquals(
            "private-verifier",
            McpOAuthAttemptStore(dir, secrets).consumeAttempt("valid-state", 200)?.codeVerifier,
        )
        assertTrue(secrets.aliases().isEmpty())
    }

    @Test fun simultaneousStoreInstancesConsumeExactlyOnce() {
        val dir = temporary.newFolder()
        val secrets = OAuthTestSecrets()
        val first = McpOAuthAttemptStore(dir, secrets)
        val second = McpOAuthAttemptStore(dir, secrets)
        first.saveAttempt(attempt())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                pool
                    .invokeAll(
                        listOf(
                            Callable { first.consumeAttempt("valid-state", 200) },
                            Callable { second.consumeAttempt("valid-state", 200) },
                        ),
                    ).map { it.get() }
            assertEquals(1, results.count { it != null })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun symlinkAndMismatchedStateAreNotConsumed() {
        val root = temporary.newFolder()
        val dir = File(root, "attempts")
        val secrets = OAuthTestSecrets()
        val store = McpOAuthAttemptStore(dir, secrets)
        val victim = File(root, "outside.json").apply { writeText(McpOAuthAttemptCodec.encode(attempt())) }
        java.nio.file.Files
            .createSymbolicLink(File(dir, "valid-state.json").toPath(), victim.toPath())
        assertNull(store.consumeAttempt("valid-state", 200))
        assertTrue(victim.exists())
        File(dir, "other-state.json").writeText(McpOAuthAttemptCodec.encode(attempt()))
        assertNull(store.consumeAttempt("other-state", 200))
    }

    @Test fun cleanupDoesNotDeleteAnotherStoresVerifier() {
        val secrets = OAuthTestSecrets()
        val first = McpOAuthAttemptStore(temporary.newFolder(), secrets)
        val second = McpOAuthAttemptStore(temporary.newFolder(), secrets)
        first.saveAttempt(attempt())
        second.cleanupExpired(20000)
        assertEquals("private-verifier", first.consumeAttempt("valid-state", 200)?.codeVerifier)
    }

    @Test fun crashAfterClaimNeverReplaysAndExpiryCleansVerifier() {
        val dir = temporary.newFolder()
        val secrets = OAuthTestSecrets()
        val store = McpOAuthAttemptStore(dir, secrets)
        store.saveAttempt(attempt())
        java.nio.file.Files.move(
            File(dir, "valid-state.json").toPath(),
            File(dir, "valid-state.fixture.claimed").toPath(),
        )
        assertNull(McpOAuthAttemptStore(dir, secrets).consumeAttempt("valid-state", 200))
        store.cleanupExpired(20000)
        assertTrue(secrets.aliases().isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun exactCallbackBindingAndDuplicateParameters() {
        val good = "helix://oauth/mcp/callback?state=valid-state&code=fixture"
        assertTrue(McpOAuthCallback.parse(good).matches(attempt()))
        assertFalse(McpOAuthCallback.parse(good.replace("/mcp/callback", "/mcp/callback/extra")).matches(attempt()))
        assertFalse(McpOAuthCallback.parse(good.replace("helix:", "evil:")).matches(attempt()))
        assertFalse(McpOAuthCallback.parse("$good&iss=https%3A%2F%2Fevil.example").matches(attempt()))
        assertThrows(IllegalArgumentException::class.java) { McpOAuthCallback.parse("$good&state=other") }
        assertThrows(IllegalArgumentException::class.java) { McpOAuthCallback.parse("$good#fragment") }
        assertThrows(Exception::class.java) { McpOAuthCallback.parse("$good&bad=%QQ") }
    }
}
