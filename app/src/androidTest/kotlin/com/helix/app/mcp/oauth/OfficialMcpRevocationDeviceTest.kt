package com.helix.app.mcp.oauth

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Explicit real-account revocation opt-in; old bearer remains in this process only. */
class OfficialMcpRevocationDeviceTest {
    @Test
    fun revokeExistingAccountAndProbeOldBearer() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("officialOAuthRevoke") == "true")
            val vendor = requireNotNull(args.getString("officialOAuthService"))
            val url =
                when (vendor) {
                    "linear" -> "https://mcp.linear.app/mcp"
                    "notion" -> "https://mcp.notion.com/mcp"
                    else -> error("Unsupported official service")
                }
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val service = c.pluginService
            val endpoint = service.list().flatMap { it.endpoints }.single { it.endpoint.url == url }
            val alias = SecretAlias("mcp.${endpoint.id}.oauth.binding")
            val before = OAuthCredentialCodec.decode(c.storage.secrets.get(alias))
            check(!before.refreshing && !before.tokens.isExpired()) { "Ready existing credential required" }
            val offer = service.clientSetup.inspect(endpoint, service.oauthRedirectUri)
            val identity = requireNotNull(service.clientSetup.remembered(offer))
            val baseline = probe(url, before.tokens.accessToken)
            check(baseline in 200..299) { "Old credential baseline unavailable; no revocation performed" }
            val result = service.revokeOAuth(endpoint)
            assertFalse(service.hasOAuthToken(endpoint))
            assertFalse(c.storage.secrets.contains(alias))
            assertFalse(c.storage.secrets.contains(SecretAlias(McpOAuthCoordinator.tokenAlias(endpoint.id))))
            assertFalse(
                c.storage.mcpServers
                    .resolve(endpoint.id)
                    .enabled,
            )
            assertEquals(identity, service.clientSetup.remembered(offer))
            val oldStatus = probe(url, before.tokens.accessToken)
            println(
                "OFFICIAL_MCP_REVOKE vendor=$vendor localCleared=true toolsDisabled=true " +
                    "clientRetained=true vendorReported=${result.vendorRevoked} vendorStatus=${result.statusCode} " +
                    "oldBearerBaseline=$baseline oldBearerAfter=$oldStatus " +
                    "oldBearerRejected=${oldStatus == 401}",
            )
        }

    private fun probe(
        url: String,
        bearer: String,
    ): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $bearer")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        connection.setRequestProperty("Content-Type", "application/json")
        val body =
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-03-26","capabilities":{},
              "clientInfo":{"name":"Helix acceptance","version":"1"}
            }}
            """.trimIndent()
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
