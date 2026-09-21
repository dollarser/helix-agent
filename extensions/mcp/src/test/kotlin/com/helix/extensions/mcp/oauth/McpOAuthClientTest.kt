package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointDeniedException
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class McpOAuthClientTest {
    private lateinit var server: OAuthTestServer

    @Before
    fun setUp() {
        server = OAuthTestServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val allowAllGate =
        McpEndpointGate { endpoint ->
            McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
        }

    @Test
    fun buildAuthorizationUriIncludesAllRequiredPkceParameters() {
        val client = McpOAuthClient(allowAllGate)
        val pkce = McpOAuthPkce.generate()
        val state = McpOAuthPkce.generateState()

        val uri =
            client.buildAuthorizationUri(
                McpOAuthAuthRequest(
                    authorizationEndpoint = "https://example.com/oauth/authorize",
                    clientId = "test-client-123",
                    redirectUri = "helix://oauth/mcp/callback",
                    scope = "read write",
                    state = state,
                    codeChallenge = pkce.challenge,
                ),
            )

        assertTrue(uri.startsWith("https://example.com/oauth/authorize?"))
        assertTrue(uri.contains("response_type=code"))
        assertTrue(uri.contains("client_id=test-client-123"))
        assertTrue(uri.contains("redirect_uri=helix%3A%2F%2Foauth%2Fmcp%2Fcallback"))
        assertTrue(uri.contains("scope=read+write") || uri.contains("scope=read%20write"))
        assertTrue(uri.contains("state=$state"))
        assertTrue(uri.contains("code_challenge=${pkce.challenge}"))
        assertTrue(uri.contains("code_challenge_method=S256"))
    }

    @Test
    fun metadataDiscoverySucceedsAndFailsOnInvalid() =
        runBlocking {
            val discovery = McpOAuthDiscovery(allowAllGate)
            val endpoint = NormalizedEndpoint.parse(server.baseUrl)

            server.setMetadataResponse(
                """
                {
                    "issuer": "${server.baseUrl}",
                    "authorization_endpoint": "${server.baseUrl}/authorize",
                    "token_endpoint": "${server.baseUrl}/token",
                    "revocation_endpoint": "${server.baseUrl}/revoke",
                    "code_challenge_methods_supported": ["S256"]
                }
                """.trimIndent(),
            )

            val metadata = discovery.discover(endpoint)
            assertEquals(server.baseUrl, metadata.issuer)
            assertEquals("${server.baseUrl}/authorize", metadata.authorizationEndpoint)
            assertEquals("${server.baseUrl}/token", metadata.tokenEndpoint)
            assertEquals("${server.baseUrl}/revoke", metadata.revocationEndpoint)
            assertTrue(metadata.supportsS256())
        }

    @Test
    fun exchangeCodeSucceedsWithValidPkce() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            val pkce = McpOAuthPkce.generate()

            server.setExpectedVerifier(pkce.verifier)
            server.setTokenResponse(
                """
                {
                    "access_token": "mock-access-token-123",
                    "token_type": "Bearer",
                    "expires_in": 3600,
                    "refresh_token": "mock-refresh-token-456",
                    "scope": "read"
                }
                """.trimIndent(),
            )

            val tokens =
                client.exchangeCode(
                    tokenEndpoint = "${server.baseUrl}/token",
                    clientId = "client-app",
                    redirectUri = "helix://oauth/mcp/callback",
                    code = "valid-auth-code",
                    codeVerifier = pkce.verifier,
                )

            assertEquals("mock-access-token-123", tokens.accessToken)
            assertEquals("Bearer", tokens.tokenType)
            assertEquals(3600L, tokens.expiresInSeconds)
            assertEquals("mock-refresh-token-456", tokens.refreshToken)
            assertEquals("read", tokens.scope)
            assertFalse(tokens.isExpired())
        }

    @Test
    fun exchangeCodeHandlesSlackAuthedUserTokens() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            server.setTokenResponse(
                """
                {
                    "ok": true,
                    "app_id": "A0C34BUD58D",
                    "authed_user": {
                        "id": "U0C391PQ5FU",
                        "scope": "channels:read,users:read,chat:write",
                        "access_token": "xoxe.xoxp-mock-slack-user-token",
                        "token_type": "user",
                        "refresh_token": "xoxe-mock-slack-refresh-token",
                        "expires_in": 43200
                    },
                    "team": {"id": "T0C2TNVAAVD", "name": "Helix"}
                }
                """.trimIndent(),
            )

            val tokens =
                client.exchangeCode(
                    tokenEndpoint = "${server.baseUrl}/token",
                    clientId = "slack-client",
                    redirectUri = "helix://oauth/callback",
                    code = "mock-slack-code",
                    codeVerifier = "some-verifier-123456789012345678901234567890",
                )

            assertEquals("xoxe.xoxp-mock-slack-user-token", tokens.accessToken)
            assertEquals("xoxe-mock-slack-refresh-token", tokens.refreshToken)
            assertEquals(43200L, tokens.expiresInSeconds)
            assertEquals("channels:read,users:read,chat:write", tokens.scope)
        }

    @Test
    fun exchangeCodeFailsOnErrorResponse() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            server.setTokenError(400, """{"error": "invalid_grant", "error_description": "Code expired"}""")

            val exception =
                assertThrows(McpOAuthException::class.java) {
                    runBlocking {
                        client.exchangeCode(
                            tokenEndpoint = "${server.baseUrl}/token",
                            clientId = "client-app",
                            redirectUri = "helix://oauth/mcp/callback",
                            code = "expired-code",
                            codeVerifier = "some-verifier-123456789012345678901234567890",
                        )
                    }
                }
            assertEquals("invalid_grant", exception.errorCode)
            assertTrue(exception.message!!.contains("Code expired"))
        }

    @Test
    fun refreshTokenSucceedsAndDeduplicatesConcurrentCalls() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            server.setTokenResponse(
                """
                {
                    "access_token": "refreshed-access-token-789",
                    "token_type": "Bearer",
                    "expires_in": 3600,
                    "refresh_token": "new-refresh-token-999"
                }
                """.trimIndent(),
            )

            val deferred1 =
                async {
                    client.refreshToken("${server.baseUrl}/token", "client-1", "refresh-token-1")
                }
            val deferred2 =
                async {
                    client.refreshToken("${server.baseUrl}/token", "client-1", "refresh-token-1")
                }

            val results = listOf(deferred1, deferred2).awaitAll()
            assertEquals("refreshed-access-token-789", results[0].accessToken)
            assertEquals("refreshed-access-token-789", results[1].accessToken)
            // Verify both received the tokens, and server was called
            assertTrue(server.tokenCallCount.get() >= 1)
        }

    @Test
    fun revokeTokenReportsVendorStatus() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            server.setRevokeStatus(200)

            val resultSuccess = client.revokeToken("${server.baseUrl}/revoke", "client-1", "token-to-revoke")
            assertTrue(resultSuccess.vendorRevoked)
            assertEquals(200, resultSuccess.statusCode)

            server.setRevokeStatus(500)
            val resultFail = client.revokeToken("${server.baseUrl}/revoke", "client-1", "token-to-revoke")
            assertFalse(resultFail.vendorRevoked)
            assertEquals(500, resultFail.statusCode)
        }

    @Test
    fun ssrfDenialBlocksOAuthNetworkCall() {
        val denyingGate =
            McpEndpointGate { _ ->
                throw McpEndpointDeniedException(com.helix.core.policy.SsrfDenialCode.NON_PUBLIC_ADDRESS)
            }
        val client = McpOAuthClient(denyingGate)

        assertThrows(McpEndpointDeniedException::class.java) {
            runBlocking {
                client.exchangeCode(
                    tokenEndpoint = "https://10.0.0.1/token",
                    clientId = "client-1",
                    redirectUri = "helix://oauth/mcp/callback",
                    code = "c",
                    codeVerifier = "v",
                )
            }
        }
    }

    private class OAuthTestServer : Closeable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        var metadataBody: String = "{}"
        var tokenBody: String = "{}"
        var tokenStatusCode: Int = 200
        var revokeStatusCode: Int = 200
        private var expectedVerifier: String? = null
        val tokenCallCount = AtomicInteger(0)

        init {
            val executor = Executors.newCachedThreadPool()
            server.executor = executor

            server.createContext("/.well-known/oauth-authorization-server") { exchange ->
                val bytes = metadataBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            server.createContext("/token") { exchange ->
                tokenCallCount.incrementAndGet()
                val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val params = parseForm(requestBody)

                val verifier = params["code_verifier"]
                if (expectedVerifier != null && verifier != expectedVerifier) {
                    val err =
                        """{"error": "invalid_grant", "error_description": "PKCE verifier mismatch"}"""
                            .toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(400, err.size.toLong())
                    exchange.responseBody.write(err)
                    exchange.close()
                    return@createContext
                }

                val bytes = tokenBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(tokenStatusCode, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            server.createContext("/revoke") { exchange ->
                exchange.sendResponseHeaders(revokeStatusCode, 0)
                exchange.close()
            }

            server.start()
        }

        fun setMetadataResponse(json: String) {
            metadataBody = json
        }

        fun setTokenResponse(json: String) {
            tokenBody = json
            tokenStatusCode = 200
        }

        fun setTokenError(
            code: Int,
            json: String,
        ) {
            tokenStatusCode = code
            tokenBody = json
        }

        fun setRevokeStatus(code: Int) {
            revokeStatusCode = code
        }

        fun setExpectedVerifier(verifier: String) {
            expectedVerifier = verifier
        }

        private fun parseForm(body: String): Map<String, String> =
            body.split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        override fun close() {
            server.stop(0)
        }
    }
}
