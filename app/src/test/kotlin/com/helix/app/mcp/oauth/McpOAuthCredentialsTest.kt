package com.helix.app.mcp.oauth

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthTokens
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class McpOAuthCredentialsTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val count = AtomicInteger()
    private val base = "http://127.0.0.1:${server.address.port}"
    private val secrets = OAuthTestSecrets()
    private val client =
        McpOAuthClient(
            McpEndpointGate { endpoint ->
                McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
            },
        )
    private val binding =
        OAuthBinding(
            "server",
            NormalizedEndpoint.parse("$base/mcp").full,
            base,
            "client",
            "$base/token",
            "$base/revoke",
            "generation",
        )
    private val config =
        McpServerConfig(
            McpServerId("server"),
            NormalizedEndpoint.parse("$base/mcp"),
            SecretAlias(McpOAuthCoordinator.tokenAlias("server")),
        )
    private var status = 200
    private var duringRefresh: () -> Unit = {}

    init {
        server.executor = executor
        server.createContext("/token") { exchange ->
            count.incrementAndGet()
            duringRefresh()
            val body =
                if (status == 200) {
                    """{"access_token":"fresh-access","expires_in":3600,"refresh_token":"rotated"}"""
                } else {
                    """{"error":"invalid_grant"}"""
                }
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/revoke") { exchange ->
            val body = """{"ok":false,"error":"invalid_auth"}"""
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun owner(): McpOAuthCredentials = McpOAuthCredentials(secrets, client)

    private fun seed(owner: McpOAuthCredentials) {
        owner.begin("server", binding.generation)
        owner.save(
            binding,
            McpOAuthTokens(
                "expired-access",
                expiresInSeconds = 1,
                refreshToken = "refresh-once",
                issuedAtMs = 1,
            ),
        )
    }

    @Test fun concurrentRefreshUsesOneRequestAndSurvivesRecreation() =
        runBlocking {
            val owner = owner()
            seed(owner)
            (1..8).map { async { owner.prepare(config) } }.awaitAll()
            assertEquals(1, count.get())
            assertEquals("fresh-access", secrets.get(SecretAlias(McpOAuthCoordinator.tokenAlias("server"))))
            owner().prepare(config)
            assertEquals(1, count.get())
        }

    @Test fun ambiguousRefreshIsNotReplayedAfterRecreation() {
        val owner = owner()
        seed(owner)
        status = 500
        assertThrows(Exception::class.java) { runBlocking { owner.prepare(config) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { owner().prepare(config) } }
        assertEquals(1, count.get())
    }

    @Test fun anotherServerCannotReuseOAuthAlias() {
        val owner = owner()
        seed(owner)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.prepare(config.copy(id = McpServerId("other-server"))) }
        }
        assertEquals(0, count.get())
    }

    @Test fun resourceChangeCannotSendOrRefreshToken() {
        val owner = owner()
        seed(owner)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.prepare(config.copy(endpoint = NormalizedEndpoint.parse("https://other.example/mcp"))) }
        }
        assertEquals(0, count.get())
    }

    @Test fun cancelledLoginCannotResurrectCredentialsFromLateRefresh() {
        val owner = owner()
        seed(owner)
        duringRefresh = { owner.clear("server") }
        assertThrows(IllegalStateException::class.java) { runBlocking { owner.prepare(config) } }
        assertFalse(owner.hasToken("server"))
        assertFalse(secrets.contains(SecretAlias(McpOAuthCoordinator.tokenAlias("server"))))
    }

    @Test fun vendorFailureStillClearsLocalCredentialsWithoutClaimingRevocation() =
        runBlocking {
            val owner = owner()
            seed(owner)
            val result = owner.revoke("server")
            assertFalse(result.vendorRevoked)
            assertFalse(owner.hasToken("server"))
            assertTrue(secrets.aliases().none { it.value.endsWith(".token") || it.value.endsWith(".binding") })
        }
}
