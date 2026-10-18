package com.helix.provider.api.wire

import com.helix.core.model.NormalizedEndpoint

/** A public distribution identifier, attached only to explicit HTTPS generation/catalog endpoints. */
class PublicClientWireClient(
    private val generationUrl: String,
    private val clientId: String,
    private val delegate: WireClient = OkHttpWireClient(followRedirects = false, retryConnections = false),
    private val catalogUrl: String? = null,
) : WireClient {
    private val canonicalUrl = NormalizedEndpoint.parse(generationUrl).full

    init {
        val endpoint = NormalizedEndpoint.parse(generationUrl)
        require(endpoint.scheme == "https") { "Public client service requires HTTPS" }
        require(catalogUrl == null || NormalizedEndpoint.parse(catalogUrl).origin == endpoint.origin) {
            "Catalog must use the same service origin"
        }
        require(clientId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid public client identifier" }
    }

    override suspend fun open(request: WireRequest): WireResponse {
        val target =
            when {
                request.method == "POST" && request.url in setOf(generationUrl, canonicalUrl) -> generationUrl

                request.method == "GET" && catalogUrl != null && request.body == null &&
                    request.url in setOf(catalogUrl, NormalizedEndpoint.parse(catalogUrl).full) -> catalogUrl

                else -> throw IllegalArgumentException("Public client target changed")
            }
        require(request.headers.keys.none { it.lowercase() in RESERVED }) { "Unexpected client authentication header" }
        return delegate.open(
            WireRequest(request.method, target, request.headers + (HEADER to clientId), request.body),
        )
    }

    companion object {
        const val HEADER = "X-Client-Id"
        private val RESERVED = setOf("x-client-id", "authorization", "proxy-authorization", "cookie")
    }
}
