package com.helix.provider.openai.chat

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest

/**
 * OpenAI Chat Completions protocol adapter (HXA-023, protocol
 * `OPENAI_CHAT_COMPLETIONS`).
 *
 * Protocol logic only: [requestBodyEncoder] encodes the internal
 * [ModelRequest] into the vendor request body, and [newDecoder] decodes the
 * vendor SSE byte stream into the internal [ModelEvent] contract. The HTTP
 * transport (endpoint from the normalized provider config, `Authorization`
 * header resolved from the Keystore-backed SecretStore, redirects/origin
 * re-check, non-2xx → [ModelEvent.Error] mapping, timeouts) is wired in
 * HXA-025 on top of this adapter — it feeds the raw response body chunks into
 * the decoder.
 *
 * Per doc 02 section 6.2 and roadmap HXA-023 this adapter is a protocol
 * island: a failure here never silently switches to the Responses protocol —
 * the protocol is fixed by the provider configuration, and a failed stream
 * surfaces as a [ModelEvent.Error] for the Agent Loop to decide on.
 */
public class OpenAiChatAdapter(
    imageResolver: ImageResolver,
) {
    public val requestBodyEncoder: ChatCompletionsRequestEncoder =
        ChatCompletionsRequestEncoder(imageResolver)

    /** One decoder per stream (one model call); never share across calls. */
    public fun newDecoder(): ChatCompletionsStreamDecoder = ChatCompletionsStreamDecoder()
}
