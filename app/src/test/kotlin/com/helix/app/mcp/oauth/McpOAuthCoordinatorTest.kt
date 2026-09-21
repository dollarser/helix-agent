package com.helix.app.mcp.oauth

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class McpOAuthCoordinatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockServer: MockOAuthServer
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var attemptStore: McpOAuthAttemptStore
    private lateinit var coordinator: McpOAuthCoordinator

    private val allowAllGate =
        McpEndpointGate { endpoint ->
            McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
        }

    @Before
    fun setUp() {
        mockServer = MockOAuthServer()
        secretStore = InMemorySecretStore()
        attemptStore = McpOAuthAttemptStore(tempFolder.newFolder("attempts"), secretStore)
        val client = McpOAuthClient(allowAllGate)
        coordinator = McpOAuthCoordinator(secretStore, attemptStore, client)
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun prepareAuthorizationSavesAttemptAndReturnsBrowserUri() {
        val metadata =
            McpOAuthServerMetadata(
                issuer = mockServer.baseUrl,
                authorizationEndpoint = "${mockServer.baseUrl}/authorize",
                tokenEndpoint = "${mockServer.baseUrl}/token",
            )

        val prepared =
            coordinator.prepareAuthorization(
                serverId = "slack-mcp",
                clientId = "slack-client-id",
                metadata = metadata,
                scope = "chat:write channels:read",
            )

        assertTrue(prepared.authUri.startsWith("${mockServer.baseUrl}/authorize?"))
        assertTrue(prepared.authUri.contains("client_id=slack-client-id"))
        assertTrue(prepared.authUri.contains("state=${prepared.state}"))
        assertTrue(prepared.authUri.contains("code_challenge="))
        assertTrue(prepared.authUri.contains("code_challenge_method=S256"))

        // Verify attempt exists in store
        val consumed = attemptStore.consumeAttempt(prepared.state)
        assertNotNull(consumed)
        assertEquals("slack-mcp", consumed!!.serverId)
    }

    @Test
    fun handleCallbackExchangesCodeAndSavesSecrets() =
        runBlocking {
            val metadata =
                McpOAuthServerMetadata(
                    issuer = mockServer.baseUrl,
                    authorizationEndpoint = "${mockServer.baseUrl}/authorize",
                    tokenEndpoint = "${mockServer.baseUrl}/token",
                )

            val prepared =
                coordinator.prepareAuthorization(
                    serverId = "slack-mcp",
                    clientId = "slack-client-id",
                    metadata = metadata,
                    scope = "chat:write",
                )

            mockServer.setTokenResponse(
                """
                {
                    "access_token": "xoxb-mock-token",
                    "token_type": "Bearer",
                    "expires_in": 7200,
                    "refresh_token": "xoxr-mock-refresh"
                }
                """.trimIndent(),
            )

            val callbackUrl = "helix://oauth/mcp/callback?code=auth-code-123&state=${prepared.state}"
            val result = coordinator.handleCallback(callbackUrl)

            assertTrue(result is McpOAuthResult.Success)
            val success = result as McpOAuthResult.Success
            assertEquals("slack-mcp", success.serverId)
            assertEquals("xoxb-mock-token", secretStore.get(SecretAlias("mcp.slack-mcp.oauth.token")))

            // Verify saved in SecretStore
            val tokenAlias = SecretAlias("mcp.slack-mcp.oauth.token")
            val refreshAlias = SecretAlias("mcp.slack-mcp.oauth.refresh")
            assertEquals("xoxb-mock-token", secretStore.get(tokenAlias))
            assertFalse(secretStore.contains(refreshAlias))
            assertTrue(secretStore.get(SecretAlias("mcp.slack-mcp.oauth.binding")).contains("xoxr-mock-refresh"))

            // Replay attempt must fail (one-time consumption)
            val replayResult = coordinator.handleCallback(callbackUrl)
            assertTrue(replayResult is McpOAuthResult.Failure)
            val replayMsg = (replayResult as McpOAuthResult.Failure).message
            assertTrue(replayMsg.contains("invalid, already consumed, or expired"))
        }

    @Test
    fun handleCallbackWithVendorErrorReturnsFailure() =
        runBlocking {
            val callbackUrl =
                "helix://oauth/mcp/callback?" +
                    "error=access_denied&error_description=User+cancelled&state=any-state"
            val result = coordinator.handleCallback(callbackUrl)

            assertTrue(result is McpOAuthResult.Failure)
        }

    @Test
    fun revokeAndClearRemovesSecretsAndCallsRevocation() =
        runBlocking {
            val tokenAlias = SecretAlias("mcp.slack-mcp.oauth.token")
            val refreshAlias = SecretAlias("mcp.slack-mcp.oauth.refresh")
            val prepared =
                coordinator.prepareAuthorization(
                    "slack-mcp",
                    "client-id",
                    McpOAuthServerMetadata(
                        mockServer.baseUrl,
                        "${mockServer.baseUrl}/authorize",
                        "${mockServer.baseUrl}/token",
                        "${mockServer.baseUrl}/revoke",
                    ),
                    "read",
                )
            mockServer.setTokenResponse("""{"access_token":"token-to-revoke","refresh_token":"refresh-to-revoke"}""")
            assertTrue(
                coordinator.handleCallback(
                    "helix://oauth/mcp/callback?state=${prepared.state}&code=fixture",
                ) is McpOAuthResult.Success,
            )

            mockServer.setRevokeStatus(200)

            val outcome =
                coordinator.revokeAndClear(
                    serverId = "slack-mcp",
                )

            assertTrue(outcome.vendorRevoked)
            assertFalse(secretStore.contains(tokenAlias))
            assertFalse(secretStore.contains(refreshAlias))
        }

    @Test
    fun hasTokenReflectsSecretStoreState() =
        runBlocking {
            assertFalse(coordinator.hasToken("slack-mcp"))
            val tokenAlias = SecretAlias("mcp.slack-mcp.oauth.token")
            secretStore.put(tokenAlias, "test-token")
            assertFalse(coordinator.hasToken("slack-mcp")) // Legacy unbound tokens require login.

            coordinator.revokeAndClear(
                serverId = "slack-mcp",
            )
            assertFalse(coordinator.hasToken("slack-mcp"))
        }

    @Test
    fun eventsFlowEmitsOnCallback() =
        runBlocking {
            var emitted: McpOAuthResult? = null
            val job =
                launch {
                    coordinator.events.collect { emitted = it }
                }

            val prepared =
                coordinator.prepareAuthorization(
                    serverId = "slack-mcp",
                    clientId = "client-id-xyz",
                    metadata =
                        McpOAuthServerMetadata(
                            issuer = mockServer.baseUrl,
                            authorizationEndpoint = "${mockServer.baseUrl}/authorize",
                            tokenEndpoint = "${mockServer.baseUrl}/token",
                        ),
                    scope = "channels:read",
                )

            mockServer.setTokenResponse(
                """
                {
                    "access_token": "flow-token-123",
                    "token_type": "Bearer"
                }
                """.trimIndent(),
            )

            val callbackUrl = "helix://oauth/mcp/callback?code=flow-code-456&state=${prepared.state}"
            coordinator.handleCallback(callbackUrl)

            delay(50)
            job.cancel()

            assertTrue(emitted is McpOAuthResult.Success)
            assertEquals("slack-mcp", (emitted as McpOAuthResult.Success).serverId)
            assertEquals("flow-token-123", secretStore.get(SecretAlias("mcp.slack-mcp.oauth.token")))
        }

    private class InMemorySecretStore : SecretStore {
        private val map = mutableMapOf<SecretAlias, String>()

        override fun put(
            alias: SecretAlias,
            secret: String,
        ) {
            map[alias] = secret
        }

        override fun get(alias: SecretAlias): String = map[alias] ?: throw IllegalArgumentException("Missing: $alias")

        override fun delete(alias: SecretAlias) {
            map.remove(alias)
        }

        override fun contains(alias: SecretAlias): Boolean = alias in map

        override fun aliases(): Set<SecretAlias> = map.keys.toSet()
    }

    private class MockOAuthServer : Closeable {
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"
        private var tokenJson: String = "{}"
        private var revokeStatus: Int = 200

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/token") { exchange ->
                val bytes = tokenJson.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            server.createContext("/revoke") { exchange ->
                exchange.sendResponseHeaders(revokeStatus, 0)
                exchange.close()
            }
            server.start()
        }

        fun setTokenResponse(json: String) {
            tokenJson = json
        }

        fun setRevokeStatus(code: Int) {
            revokeStatus = code
        }

        override fun close() {
            server.stop(0)
        }
    }
}
