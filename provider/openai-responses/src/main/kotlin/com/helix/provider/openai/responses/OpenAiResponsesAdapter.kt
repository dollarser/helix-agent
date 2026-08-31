package com.helix.provider.openai.responses

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest

/**
 * OpenAI Responses API adapter (HXA-022, protocol `OPENAI_RESPONSES`).
 *
 * Protocol logic only: [requestBody] encodes the internal [ModelRequest] into
 * the vendor request body, and [newDecoder] decodes the vendor SSE byte stream
 * into the internal [ModelEvent] contract. The HTTP transport (endpoint from
 * the normalized provider config, `Authorization` header resolved from the
 * Keystore-backed SecretStore, redirects/origin re-check, non-2xx →
 * [ModelEvent.Error] mapping, timeouts) is wired in HXA-025 on top of this
 * adapter — it feeds the raw response body chunks into the decoder.
 *
 * Per doc 02 section 6.2 this adapter is a protocol island: a failure here
 * never falls back to Chat Completions or any other protocol; the protocol is
 * fixed by the provider configuration.
 */
public class OpenAiResponsesAdapter(
    imageResolver: ImageResolver,
) {
    public val requestBodyEncoder: ResponsesRequestEncoder = ResponsesRequestEncoder(imageResolver)

    /** One decoder per stream (one model call); never share across calls. */
    public fun newDecoder(): ResponsesStreamDecoder = ResponsesStreamDecoder()
}
