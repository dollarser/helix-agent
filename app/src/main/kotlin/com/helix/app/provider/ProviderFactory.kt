package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.provider.anthropic.AnthropicProvider
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.openai.chat.OpenAiChatProvider
import com.helix.provider.openai.responses.OpenAiResponsesProvider
import com.helix.provider.anthropic.ImageResolver as AnthropicImageResolver
import com.helix.provider.openai.chat.ImageResolver as ChatImageResolver
import com.helix.provider.openai.responses.ImageResolver as ResponsesImageResolver

/**
 * Builds the concrete protocol adapter for a persisted [ProviderConfig]
 * (HXA-028 production wiring of the HXA-025 stack). The three protocols are
 * INDEPENDENT adapters — there is no protocol fallback on failure
 * (doc 10 section 2.1; doc 02 section 6.2).
 *
 * Credential resolution: the Keystore-backed [CredentialLookup] reads the
 * secret at request time (never at UI construction, never into UI state —
 * NFR-007). For keyless providers (template `credentialRequired = false`,
 * e.g. local Ollama) the config stores [NO_KEY_ALIAS]; the lookup returns a
 * fixed non-secret placeholder for that alias. The adapters still send their
 * protocol auth header shape, and local servers ignore it (verified in the
 * HXA-027 Ollama smoke, which sent a bearer value the server ignored).
 */
class ProviderFactory(
    private val credentials: CredentialLookup,
    private val wire: WireClient,
) {
    fun create(config: ProviderConfig): ModelProvider =
        when (config.protocol) {
            ProviderProtocol.OPENAI_RESPONSES -> OpenAiResponsesProvider(config, credentials, wire, responsesImages)
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatProvider(config, credentials, wire, chatImages)
            ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicProvider(config, credentials, wire, anthropicImages)
        }

    private val chatImages =
        ChatImageResolver {
            unsupportedImage()
        }

    private val responsesImages =
        ResponsesImageResolver {
            unsupportedImage()
        }

    private val anthropicImages =
        AnthropicImageResolver {
            unsupportedImage()
        }

    /**
     * M2 chat input is text-only (file/image input arrives with a later
     * milestone); the resolvers therefore fail fast and typed when — and only
     * when — a request actually carries images, which the M2 send path cannot
     * produce. This is a documented scope boundary, not a placeholder: the
     * error names the missing capability.
     */
    private fun unsupportedImage(): Nothing =
        error("images are not supported by the M2 chat UI; file/image input arrives with a later milestone")

    companion object {
        /**
         * The stored alias of a keyless provider — a VALID
         * [com.helix.core.model.SecretAlias] (the value class requires a
         * letter/digit first char, so a bare "-" would fail closed in the
         * storage spec validation). The CredentialLookup returns a fixed
         * non-secret [NO_KEY_PLACEHOLDER] for it; adapters still send the auth
         * header shape and local keyless servers ignore it (HXA-027 smoke).
         */
        const val NO_KEY_ALIAS = "no-key"

        /** A non-secret bearer value for keyless providers (their servers ignore it). */
        const val NO_KEY_PLACEHOLDER = "helix-no-key"

        /** The default [WireClient] (OkHttp, HXA-025): 10s connect, 120s read, 8 MiB body. */
        fun defaultWire(): WireClient = OkHttpWireClient()
    }
}
