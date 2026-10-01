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
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OAuthDevicePreparationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancelledDuringDeviceRequestDoesNotPublishReturnedAttempt() {
        val fixture = fixture()
        fixture.intercept = { fixture.coordinator.cancel("server") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.coordinator.requestDeviceAuth("server", metadata, RESOURCE, "client", "read") }
        }
        assertEquals(1, fixture.calls)
        assertEquals(
            0,
            fixture.directory
                .listFiles()
                .orEmpty()
                .count { it.extension == "json" },
        )
    }

    @Test fun alreadyCancelledDevicePreparationDoesNotContactService() {
        val fixture = fixture()
        val preparation = fixture.coordinator.beginPreparation("server")
        fixture.coordinator.cancel("server")
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.coordinator.requestDeviceAuth("server", metadata, RESOURCE, "client", "read", preparation)
            }
        }
        assertEquals(0, fixture.calls)
    }

    private fun fixture() = Fixture(temporary.newFolder())

    private class Fixture(
        val directory: java.io.File,
    ) {
        var intercept: () -> Unit = {}
        var calls = 0
        private val gate = McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) }
        private val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    calls++
                    intercept()
                    val body =
                        """
                        {"device_code":"fixture","user_code":"CODE",
                         "verification_uri":"https://issuer.example/verify","expires_in":300}
                        """.trimIndent()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }.build()
        private val secrets = OAuthTestSecrets()
        val coordinator =
            McpOAuthCoordinator(secrets, McpOAuthAttemptStore(directory, secrets), McpOAuthClient(gate, http))
    }

    private companion object {
        const val RESOURCE = "https://resource.example/mcp"
        val metadata =
            McpOAuthServerMetadata(
                "https://issuer.example",
                "https://issuer.example/auth",
                "https://issuer.example/token",
                deviceAuthorizationEndpoint = "https://issuer.example/device",
            )
    }
}
