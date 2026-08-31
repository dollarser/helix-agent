package com.helix.provider.openai.chat

import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.provider.api.StreamDecoder
import com.helix.provider.api.WireModelProvider
import com.helix.provider.api.resolveCredential
import com.helix.provider.api.wire.WireClient

/**
 * The OpenAI Chat Completions provider (HXA-025): wires the HXA-023 protocol
 * island (request encoder + SSE reader + stream decoder) to the shared
 * transport skeleton.
 *
 * Wire contract (protocol fixed by the configuration — no fallback to another
 * protocol on failure):
 * - `POST {endpoint.path}/chat/completions` with `stream: true` (baked into
 *   the encoder body), `Authorization: Bearer <credential>`; the
 *   OpenAI-compatible generic template (provider doc section 2.3) uses the
 *   same contract with a custom endpoint;
 * - `GET {endpoint.path}/models` for the model list (phase 2) and the
 *   phase-1 configuration check;
 * - the endpoint path is the API root (e.g. `http://127.0.0.1:11434/v1` for
 *   the Ollama template); the credential resolves at call time through the
 *   injected [CredentialLookup] and is never logged.
 */
public class OpenAiChatProvider(
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
        encoder = ChatCompletionsRequestEncoder(imageResolver),
        newDecoder = { ChatCompletionsStreamDecoder() },
        secretAlias = config.secretAlias,
        extraHeaders = config.headers,
    ) {
    init {
        require(config.protocol == ProviderProtocol.OPENAI_CHAT_COMPLETIONS) {
            "OpenAiChatProvider requires protocol OPENAI_CHAT_COMPLETIONS, got ${config.protocol}"
        }
    }

    override fun streamPath(): String = STREAM_PATH

    override fun modelsPath(): String = MODELS_PATH

    override fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${resolveCredential(credentials, secretAlias)}")

    internal companion object {
        const val STREAM_PATH = "chat/completions"
        const val MODELS_PATH = "models"
    }
}
