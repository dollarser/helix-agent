package com.helix.provider.anthropic

import com.helix.core.model.ModelEvent

/**
 * Anthropic Messages protocol adapter (HXA-024).
 *
 * Independent implementation of the protocol island (roadmap HXA-024: the
 * protocol is fixed by the provider configuration —
 * [com.helix.core.model.ProviderProtocol.ANTHROPIC_MESSAGES] — and a failed
 * Anthropic request NEVER silently switches to any OpenAI protocol):
 * - [requestBodyEncoder] turns the internal [com.helix.core.model.ModelRequest]
 *   into the Messages request body (top-level `system`, strict role
 *   alternation, merged tool-result turns, mandatory `max_tokens`,
 *   `thinking` budgets);
 * - [newDecoder] builds one [AnthropicStreamDecoder] per HTTP stream: the
 *   transport layer (HXA-025) feeds raw body chunks and passes the produced
 *   [ModelEvent] list straight to the Agent Loop (doc 10 section 2.1).
 *
 * The transport owns HTTP, the `x-api-key` / `anthropic-version` headers and
 * the non-2xx error mapping (HXA-025); this module never sees credentials.
 */
public class AnthropicAdapter(
    imageResolver: ImageResolver,
) {
    public val requestBodyEncoder: AnthropicRequestEncoder =
        AnthropicRequestEncoder(imageResolver)

    public fun newDecoder(): AnthropicStreamDecoder = AnthropicStreamDecoder()
}
