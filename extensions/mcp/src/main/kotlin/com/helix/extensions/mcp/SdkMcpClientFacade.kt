package com.helix.extensions.mcp

import com.helix.core.model.NormalizedEndpoint
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SdkMcpClientFacade(
    private val clientName: String,
    private val clientVersion: String,
    private val authenticatedEndpoint: NormalizedEndpoint? = null,
    private val bearerToken: String? = null,
    private val networkPermit: McpNetworkPermit? = null,
    private val httpClientFactory: (NormalizedEndpoint?, String?, McpNetworkPermit?) -> HttpClient = ::newOkHttpClient,
) : McpClientFacade {
    override suspend fun connect(endpointUrl: String): McpClientSession {
        require(endpointUrl.isNotBlank()) { "endpointUrl must not be blank" }

        require((authenticatedEndpoint == null) == (bearerToken == null)) {
            "authenticated endpoint and bearer token must be provided together"
        }
        val httpClient = httpClientFactory(authenticatedEndpoint, bearerToken, networkPermit)
        val sdkClient =
            Client(
                clientInfo = Implementation(name = clientName, version = clientVersion),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        val sdkTransport = StreamableHttpClientTransport(client = httpClient, url = endpointUrl)
        val transport = NegotiatedProtocolTransport(sdkTransport)

        var sessionCreated = false
        try {
            sdkClient.connect(transport)
            val serverVersion = checkNotNull(sdkClient.serverVersion) { "MCP server identity missing after initialize" }
            val protocolVersion = transport.negotiatedProtocolVersion
            check(!protocolVersion.isNullOrBlank()) { "MCP protocol version missing after initialize" }
            val session =
                SdkMcpClientSession(
                    sdkClient = sdkClient,
                    httpClient = httpClient,
                    capabilities = sdkClient.serverCapabilities ?: ServerCapabilities(),
                    identity =
                        McpServerIdentity(
                            name = serverVersion.name,
                            version = serverVersion.version,
                            negotiatedProtocolVersion = protocolVersion,
                        ),
                )
            sessionCreated = true
            return session
        } finally {
            if (!sessionCreated) {
                withContext(NonCancellable) {
                    sdkClient.close()
                    httpClient.close()
                }
            }
        }
    }
}
