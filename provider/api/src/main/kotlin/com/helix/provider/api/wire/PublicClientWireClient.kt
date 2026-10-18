package com.helix.provider.api.wire

import com.helix.core.model.NormalizedEndpoint

/** A public distribution identifier, attached only to one exact HTTPS generation endpoint. */
class PublicClientWireClient(
    private val generationUrl: String,
    private val clientId: String,
    private val delegate: WireClient = OkHttpWireClient(followRedirects = false, retryConnections = false),
) : WireClient {
    private val canonicalUrl = NormalizedEndpoint.parse(generationUrl).full

    init {
        val endpoint = NormalizedEndpoint.parse(generationUrl)
        require(endpoint.scheme == "https") { "Public client service requires HTTPS" }
        require(clientId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid public client identifier" }
    }

    override suspend fun open(request: WireRequest): WireResponse {
        require(request.url in setOf(generationUrl, canonicalUrl) && request.method == "POST") {
            "Public client target changed"
        }
        require(request.headers.keys.none { it.lowercase() in RESERVED }) { "Unexpected client authentication header" }
        return delegate.open(
            WireRequest(request.method, generationUrl, request.headers + (HEADER to clientId), request.body),
        )
    }

    companion object {
        const val HEADER = "X-Client-Id"
        private val RESERVED = setOf("x-client-id", "authorization", "proxy-authorization", "cookie")
    }
}
