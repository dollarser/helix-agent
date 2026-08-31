package com.helix.provider.anthropic

import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.provider.api.StreamDecoder
import com.helix.provider.api.WireModelProvider
import com.helix.provider.api.resolveCredential
import com.helix.provider.api.wire.WireClient

/**
 * The Anthropic Messages API provider (HXA-025): wires the HXA-024 protocol
 * island (request encoder + typed SSE reader + stream decoder) to the shared
 * transport skeleton.
 *
 * Wire contract (protocol fixed by the configuration — no fallback to another
 * protocol on failure):
 * - `POST {endpoint.path}/messages` with `stream: true` (baked into the
 *   encoder body); authentication is `x-api-key: <credential>` plus
 *   `anthropic-version: 2023-06-01` — NOT `Authorization: Bearer` (the vendor
 *   contract);
 * - the official API has no model-list endpoint: [com.helix.provider.api.ModelProvider.listModels]
 *   reports [com.helix.provider.api.ModelCatalogResult.Unsupported] and the
 *   phase-1 configuration check falls back to a minimal stream;
 * - the endpoint is the API origin (e.g. `https://api.anthropic.com`); the
 *   credential resolves at call time through the injected [CredentialLookup]
 *   and is never logged.
 */
public class AnthropicProvider(
    config: ProviderConfig,
    credentials: CredentialLookup,
    wire: WireClient,
    imageResolver: ImageResolver,
) : WireModelProvider(
        descriptor =
            ProviderDescriptor(
                id = config.id,
                displayName = config.displayName,
                protocol = config.protocol,
                model = config.model,
                endpoint = config.endpoint,
            ),
        credentials = credentials,
        wire = wire,
        encoder = AnthropicRequestEncoder(imageResolver),
        newDecoder = { AnthropicStreamDecoder() },
        secretAlias = config.secretAlias,
        extraHeaders = config.headers,
    ) {
    init {
        require(config.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
            "AnthropicProvider requires protocol ANTHROPIC_MESSAGES, got ${config.protocol}"
        }
    }

    override fun streamPath(): String = STREAM_PATH

    override fun modelsPath(): String? = null

    override fun authHeaders(): Map<String, String> =
        mapOf(
            "x-api-key" to resolveCredential(credentials, secretAlias),
            "anthropic-version" to ANTHROPIC_VERSION,
        )

    internal companion object {
        const val STREAM_PATH = "messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
