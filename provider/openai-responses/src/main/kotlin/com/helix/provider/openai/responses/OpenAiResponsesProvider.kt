package com.helix.provider.openai.responses

import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.provider.api.StreamDecoder
import com.helix.provider.api.WireModelProvider
import com.helix.provider.api.resolveCredential
import com.helix.provider.api.wire.WireClient

/**
 * The OpenAI Responses API provider (HXA-025): wires the HXA-022 protocol island
 * (request encoder + SSE reader + stream decoder) to the shared transport
 * skeleton.
 *
 * Wire contract (protocol fixed by the configuration — no fallback to another
 * protocol on failure):
 * - `POST {endpoint.path}/responses` with `stream: true` (baked into the
 *   encoder body), `Authorization: Bearer <credential>`;
 * - `GET {endpoint.path}/models` for the model list (phase 2) and the
 *   phase-1 configuration check (an authenticated cheap call);
 * - the endpoint path is the API root (e.g. `https://api.openai.com/v1`);
 *   the credential resolves at call time through the injected
 *   [CredentialLookup] (the HXA-020 Keystore-backed store) and is never
 *   logged.
 */
public class OpenAiResponsesProvider(
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
        encoder = ResponsesRequestEncoder(imageResolver),
        newDecoder = { ResponsesStreamDecoder() },
        secretAlias = config.secretAlias,
        extraHeaders = config.headers,
    ) {
    init {
        require(config.protocol == ProviderProtocol.OPENAI_RESPONSES) {
            "OpenAiResponsesProvider requires protocol OPENAI_RESPONSES, got ${config.protocol}"
        }
    }

    override fun streamPath(): String = STREAM_PATH

    override fun modelsPath(): String = MODELS_PATH

    override fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${resolveCredential(credentials, secretAlias)}")

    internal companion object {
        const val STREAM_PATH = "responses"
        const val MODELS_PATH = "models"
    }
}
