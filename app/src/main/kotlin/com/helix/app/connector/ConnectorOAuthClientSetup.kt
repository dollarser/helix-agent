package com.helix.app.connector

import com.helix.app.plugin.InstalledEndpoint
import com.helix.app.plugin.PluginService
import com.helix.extensions.mcp.oauth.McpOAuthClientIdentity
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata

/** Immutable setup offer; server metadata is checked again before an external effect. */
data class OAuthClientSetupOffer(
    val endpointId: String,
    val endpointUrl: String,
    val redirect: String,
    val metadata: McpOAuthServerMetadata,
)

class ConnectorOAuthClientSetup(
    private val service: PluginService,
) {
    suspend fun inspect(
        endpoint: InstalledEndpoint,
        redirect: String,
    ): OAuthClientSetupOffer {
        requireNotNull(service.oauthCoordinator).requireRedirect(redirect)
        return OAuthClientSetupOffer(endpoint.id, endpoint.endpoint.url, redirect, service.getOAuthMetadata(endpoint))
    }

    fun remembered(offer: OAuthClientSetupOffer): McpOAuthClientIdentity? =
        requireNotNull(service.oauthCoordinator).clients.remembered(offer.metadata, offer.redirect)

    suspend fun useDocument(
        endpoint: InstalledEndpoint,
        offer: OAuthClientSetupOffer,
        url: String,
    ): McpOAuthClientIdentity {
        validate(endpoint, offer)
        return requireNotNull(
            service.oauthCoordinator,
        ).clients.useDocument(endpoint.id, offer.metadata, offer.redirect, url)
    }

    suspend fun register(
        endpoint: InstalledEndpoint,
        offer: OAuthClientSetupOffer,
    ): McpOAuthClientIdentity {
        validate(endpoint, offer)
        return requireNotNull(service.oauthCoordinator).clients.register(endpoint.id, offer.metadata, offer.redirect)
    }

    fun forget(offer: OAuthClientSetupOffer) {
        requireNotNull(service.oauthCoordinator).clients.forget(offer.endpointId, offer.metadata.issuer, offer.redirect)
    }

    private suspend fun validate(
        endpoint: InstalledEndpoint,
        offer: OAuthClientSetupOffer,
    ) {
        require(endpoint.id == offer.endpointId && endpoint.endpoint.url == offer.endpointUrl)
        requireNotNull(service.oauthCoordinator).requireRedirect(offer.redirect)
        require(service.getOAuthMetadata(endpoint) == offer.metadata) { "OAUTH_METADATA_CHANGED_REVIEW_AGAIN" }
    }
}
