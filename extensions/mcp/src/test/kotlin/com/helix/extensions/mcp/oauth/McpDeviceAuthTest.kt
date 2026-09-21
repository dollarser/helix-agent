package com.helix.extensions.mcp.oauth

import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class McpDeviceAuthTest {
    private lateinit var server: DeviceAuthTestServer

    @Before
    fun setUp() {
        server = DeviceAuthTestServer()
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
    fun requestDeviceCodeSucceeds() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)
            server.setDeviceResponse(
                """
                {
                    "device_code": "dev-123456",
                    "user_code": "WDJB-MJHT",
                    "verification_uri": "https://github.com/login/device",
                    "expires_in": 900,
                    "interval": 5
                }
                """.trimIndent(),
            )

            val resp =
                client.requestDeviceCode(
                    deviceEndpoint = "${server.baseUrl}/device/code",
                    clientId = "test-client",
                    scope = "read:user",
                )

            assertEquals("dev-123456", resp.deviceCode)
            assertEquals("WDJB-MJHT", resp.userCode)
            assertEquals("https://github.com/login/device", resp.verificationUri)
            assertEquals(900L, resp.expiresInSeconds)
            assertEquals(5L, resp.intervalSeconds)
        }

    @Test
    fun pollDeviceTokenHandlesPendingAndSuccess() =
        runBlocking {
            val client = McpOAuthClient(allowAllGate)

            // 1. Pending
            server.setTokenResponse(
                400,
                """{"error": "authorization_pending", "error_description": "User has not yet authorized"}""",
            )
            val pendingResult =
                client.pollDeviceTokenOnce(
                    tokenEndpoint = "${server.baseUrl}/token",
                    clientId = "test-client",
                    deviceCode = "dev-123456",
                )
            assertTrue(pendingResult is McpDevicePollResult.Pending)

            // 2. Slow down
            server.setTokenResponse(
                400,
                """{"error": "slow_down", "error_description": "Polling too fast"}""",
            )
            val slowDownResult =
                client.pollDeviceTokenOnce(
                    tokenEndpoint = "${server.baseUrl}/token",
                    clientId = "test-client",
                    deviceCode = "dev-123456",
                )
            assertTrue(slowDownResult is McpDevicePollResult.SlowDown)

            // 3. Success
            server.setTokenResponse(
                200,
                """
                {
                    "access_token": "ghu_mock_access_token_123",
                    "token_type": "bearer",
                    "scope": "read:user"
                }
                """.trimIndent(),
            )
            val successResult =
                client.pollDeviceTokenOnce(
                    tokenEndpoint = "${server.baseUrl}/token",
                    clientId = "test-client",
                    deviceCode = "dev-123456",
                )
            assertTrue(successResult is McpDevicePollResult.Success)
            val tokens = (successResult as McpDevicePollResult.Success).tokens
            assertEquals("ghu_mock_access_token_123", tokens.accessToken)
            assertEquals("read:user", tokens.scope)
        }

    private class DeviceAuthTestServer : Closeable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        private var deviceBody: String = ""
        private var tokenBody: String = ""
        private var tokenStatus: Int = 200

        init {
            server.createContext("/device/code") { exchange ->
                val bytes = deviceBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            server.createContext("/token") { exchange ->
                val bytes = tokenBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(tokenStatus, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            server.start()
        }

        fun setDeviceResponse(json: String) {
            deviceBody = json
        }

        fun setTokenResponse(
            status: Int,
            json: String,
        ) {
            tokenStatus = status
            tokenBody = json
        }

        override fun close() {
            server.stop(0)
        }
    }
}
