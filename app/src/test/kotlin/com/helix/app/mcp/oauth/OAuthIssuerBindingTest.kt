package com.helix.app.mcp.oauth

import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class OAuthIssuerBindingTest {
    @Test fun cancelledOrReplacedPreparationCannotCreateALaterAttempt() {
        val secrets = OAuthTestSecrets()
        val directory = temporary.newFolder()
        val store = McpOAuthAttemptStore(directory, secrets)
        val coordinator = McpOAuthCoordinator(secrets, store, client(AtomicInteger()))
        val preparation = coordinator.beginPreparation("server")
        coordinator.cancel("server")
        assertThrows(IllegalStateException::class.java) {
            coordinator.prepareAuthorization("server", "client", metadata(), "read", preparationId = preparation)
        }
        val replaced = coordinator.beginPreparation("server")
        val fresh = coordinator.beginPreparation("server")
        val reopened = McpOAuthCoordinator(secrets, McpOAuthAttemptStore(directory, secrets), client(AtomicInteger()))
        assertThrows(IllegalStateException::class.java) {
            reopened.prepareAuthorization("server", "client", metadata(), "read", preparationId = replaced)
        }
        val prepared = reopened.prepareAuthorization("server", "client", metadata(), "read", preparationId = fresh)
        assertNotNull(store.peekAttempt(prepared.state))
    }

    @get:Rule val temporary = TemporaryFolder()

    @Test fun advertisedIssuerRequirementSurvivesReopenAndProtectsErrorResponses() =
        runBlocking {
            val secrets = OAuthTestSecrets()
            val directory = temporary.newFolder()
            val calls = AtomicInteger()
            val client = client(calls)
            val store = McpOAuthAttemptStore(directory, secrets)
            val coordinator = McpOAuthCoordinator(secrets, store, client)
            val auth = coordinator.prepareAuthorization("server", "client", metadata(), "read")
            val reopened = McpOAuthAttemptStore(directory, secrets)
            assertTrue(requireNotNull(reopened.peekAttempt(auth.state)).issuerParameterRequired)
            val later = McpOAuthCoordinator(secrets, reopened, client)
            listOf(
                "",
                "&iss=https%3A%2F%2Fissuer.example%3A443",
                "&iss=https%3A%2F%2FISSUER.example",
                "&iss=https%3A%2F%2Fissuer.example%2F",
            ).forEach { iss ->
                val callback = "helix://oauth/mcp/callback?state=${auth.state}&error=access_denied$iss"
                val result = later.handleCallback(callback)
                assertTrue(result is McpOAuthResult.Failure)
                assertNotNull(reopened.peekAttempt(auth.state))
            }
            assertEquals(0, calls.get())
            val issuer = URLEncoder.encode(ISSUER, "UTF-8")
            val good = later.handleCallback("helix://oauth/mcp/callback?state=${auth.state}&code=fixture&iss=$issuer")
            assertTrue(good is McpOAuthResult.Success)
            assertEquals(1, calls.get())
            assertEquals(null, reopened.peekAttempt(auth.state))
        }

    @Test fun optionalIssuerCanBeAbsentButCannotBeWrong() {
        val attempt = sample().copy(issuerParameterRequired = false)
        val callback = "helix://oauth/mcp/callback?state=fixture&code=code"
        assertTrue(McpOAuthCallback.parse(callback).matches(attempt))
        assertFalse(McpOAuthCallback.parse("$callback&iss=https%3A%2F%2Fother.example").matches(attempt))
    }

    @Test fun oldOrMalformedAttemptNeverDowngradesIssuerRequirement() {
        val encoded = McpOAuthAttemptCodec.encode(sample())
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuthAttemptCodec.decode(encoded.replace("\"version\":2", "\"version\":1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuthAttemptCodec.decode(
                encoded.replace("\"issuerParameterRequired\":true", "\"issuerParameterRequired\":\"true\""),
            )
        }
        assertTrue(McpOAuthAttemptCodec.decode(encoded).issuerParameterRequired)
    }

    @Test fun rejectedCallbackDoesNotIssueTokensOrConsumeAnotherAttempt() =
        runBlocking {
            val secrets = OAuthTestSecrets()
            val calls = AtomicInteger()
            val store = McpOAuthAttemptStore(temporary.newFolder(), secrets)
            val coordinator = McpOAuthCoordinator(secrets, store, client(calls))
            val prepared = coordinator.prepareAuthorization("server", "client", metadata(), "read")
            val result = coordinator.handleCallback("helix://oauth/mcp/callback?state=${prepared.state}&code=fixture")
            assertTrue(result is McpOAuthResult.Failure)
            assertFalse(coordinator.hasToken("server"))
            assertNotNull(store.peekAttempt(prepared.state))
            assertEquals(0, calls.get())
        }

    private fun client(calls: AtomicInteger): McpOAuthClient {
        val gate = McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) }
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    calls.incrementAndGet()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message(
                            "fixture",
                        ).body("""{"access_token":"fixture-only","token_type":"Bearer"}""".toResponseBody())
                        .build()
                }.build()
        return McpOAuthClient(gate, http)
    }

    private fun metadata() =
        McpOAuthServerMetadata(
            ISSUER,
            "$ISSUER/authorize",
            "$ISSUER/token",
            authorizationResponseIssParameterSupported = true,
        )

    private fun sample() =
        McpOAuthAttempt(
            "id",
            "server",
            ISSUER,
            "$ISSUER/token",
            "client",
            "helix://oauth/mcp/callback",
            "read",
            "fixture",
            "verifier",
            100,
            1000,
            issuerParameterRequired = true,
        )

    private companion object {
        const val ISSUER = "https://issuer.example"
    }
}
