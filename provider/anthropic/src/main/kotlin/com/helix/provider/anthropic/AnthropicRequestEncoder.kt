package com.helix.provider.anthropic

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.RequestEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Request payload resolved from an [ImageReference] by the [ImageResolver]
 * (HXA-024). Independent boundary type of the Anthropic protocol island
 * (doc 10 section 2.5: the Agent Core never touches vendor DTOs or raw
 * payloads; doc 02 section 4: the internal contract carries no inline image
 * bytes).
 */
public sealed interface ImagePayload {
    /**
     * Base64-encoded bytes. The `media_type` sent on the wire comes from
     * [ImageReference.mediaType] (the closed allowlist), not from the payload.
     */
    public data class Base64(
        val value: String,
    ) : ImagePayload

    /** A public HTTP(S) URL the vendor may fetch. */
    public data class Url(
        val value: String,
    ) : ImagePayload
}

/**
 * Resolves an internal [ImageReference] into the bytes the Anthropic request
 * carries. The implementation is injected at adapter construction (the
 * ContentStore-backed resolver lands with the transport wiring, HXA-025); a
 * failure for a referenced image must throw — the encoder propagates it and
 * the request is never sent with a missing image (fail closed).
 */
public fun interface ImageResolver {
    public fun resolve(reference: ImageReference): ImagePayload
}

/**
 * Encodes an internal [ModelRequest] into an Anthropic Messages request body
 * (HXA-024, doc 10 section 2.1/2.5). Independent implementation of the
 * protocol island: this adapter never falls back to any OpenAI protocol, and
 * the protocol is fixed by the provider configuration
 * (`ANTHROPIC_MESSAGES`).
 *
 * Shape mapping (Anthropic differs from both OpenAI wire formats):
 * - the top-level [ModelRole.SYSTEM] message(s) leave [ModelRequest.messages]
 *   and become the single top-level `system` field (multiple system messages
 *   join with a blank line, in order);
 * - a USER message without images encodes `content` as a plain string; with
 *   images it becomes a block array — one `text` block first, then one
 *   `image` block per image (`source.type` `base64` or `url`);
 * - an ASSISTANT message encodes as a plain string (assistant tool calls are
 *   not representable in the internal contract — see [encode] for the
 *   TOOL message consequence);
 * - a run of consecutive TOOL messages merges into ONE user message whose
 *   content is a block array of `tool_result` blocks
 *   (`tool_use_id` = the internal call id, `content` = the result text),
 *   in the original message order. The merge exists because Anthropic
 *   requires strict role alternation and the tool results of one assistant
 *   turn belong to a single user turn;
 * - tools use the flat `input_schema` field (not a nested `function`);
 * - `max_tokens` is MANDATORY on the wire: a null [ModelRequest.maxOutputTokens]
 *   sends [DEFAULT_MAX_TOKENS] (reversible implementation choice, recorded in
 *   the completion record);
 * - `reasoning` maps to the `thinking` budget: the LOW/MEDIUM/HIGH budgets
 *   are clamped below `max_tokens` with a margin; an infeasible combination
 *   throws [IllegalArgumentException] (fail closed) instead of silently
 *   dropping the requested reasoning;
 * - [ModelRequest.seed] is NOT sent: the Messages API has no seed parameter
 *   (recorded as a capability gap for the HXA-025 snapshot);
 * - `temperature` and `stop_sequences` are encoded when set; `stream` is
 *   always `true` (the internal contract is streaming-only).
 *
 * Ordering constraints (validated, fail closed with [IllegalArgumentException]):
 * the first non-system message must be USER; after the TOOL run merge the
 * sequence must strictly alternate USER/ASSISTANT — which is exactly the
 * vendor requirement that every tool result answers the preceding assistant
 * turn. The internal contract cannot carry the assistant's `tool_use` blocks
 * (HXA-021: tool association lives on the TOOL message only), so a vendor
 * request containing TOOL messages is only wire-valid once the runtime
 * request builder (M2 latter half) supplies the paired assistant turn; this
 * encoder encodes the result side faithfully and rejects an ordering that
 * would not hold on the wire anyway.
 *
 * Stateless (doc 10 section 2.5): every request carries the full conversation
 * — no server-side session. The body never contains a credential; the
 * transport layer (HXA-025) adds the `x-api-key` and `anthropic-version`
 * headers.
 */
public class AnthropicRequestEncoder(
    public val imageResolver: ImageResolver,
) : RequestEncoder {
    /** Encode the request body as a compact JSON string. */
    public override fun encode(request: ModelRequest): String {
        val body =
            buildJsonObject {
                put("model", request.model)
                put("max_tokens", maxTokensOf(request))
                val system = systemOf(request)
                if (system != null) put("system", system)
                putJsonArray("messages") {
                    messageRuns(request.messages).forEach { run ->
                        add(runElement(run, imageResolver))
                    }
                }
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name.value)
                                    put("description", tool.description)
                                    put("input_schema", parseSchema(tool.inputSchemaJson))
                                },
                            )
                        }
                    }
                }
                put("stream", true)
                request.temperature?.let { put("temperature", it) }
                if (request.stopSequences.isNotEmpty()) {
                    putJsonArray("stop_sequences") { request.stopSequences.forEach { add(JsonPrimitive(it)) } }
                }
                thinkingOf(request)?.let { put("thinking", it) }
            }
        return body.toString()
    }

    private fun systemOf(request: ModelRequest): String? =
        request.messages
            .filter { it.role == ModelRole.SYSTEM }
            .joinToString("\n\n") { it.text }
            .ifEmpty { null }

    /**
     * Splits the message sequence into runs: a run is either one
     * USER/ASSISTANT/SYSTEM message or a maximal run of consecutive TOOL
     * messages (which merge into a single user message on the wire).
     * SYSTEM messages are dropped (top-level `system` field).
     */
    private fun messageRuns(messages: List<ModelMessage>): List<Run> {
        val runs = ArrayList<Run>()
        var toolRun = ArrayList<ModelMessage>()
        for (message in messages) {
            when (message.role) {
                ModelRole.SYSTEM -> {
                    if (toolRun.isNotEmpty()) {
                        runs += Run.tool(toolRun)
                        toolRun = ArrayList()
                    }
                }

                ModelRole.TOOL -> {
                    toolRun += message
                }

                else -> {
                    if (toolRun.isNotEmpty()) {
                        runs += Run.tool(toolRun)
                        toolRun = ArrayList()
                    }
                    runs += Run.single(message)
                }
            }
        }
        if (toolRun.isNotEmpty()) runs += Run.tool(toolRun)
        validateOrdering(runs)
        return runs
    }

    /**
     * Ordering constraint (Anthropic requires strict role alternation, and a
     * tool result must answer the immediately preceding assistant turn):
     * the first run must be USER and adjacent runs must alternate
     * USER/ASSISTANT — a TOOL run counts as a USER turn.
     */
    private fun validateOrdering(runs: List<Run>) {
        require(runs.isNotEmpty()) { "request carries no user/assistant messages" }
        val first = runs.first()
        require(first.kind == ModelRole.USER) {
            "the first message of an Anthropic conversation must be user (got ${first.kind})"
        }
        var previous = first.kind
        for (run in runs.drop(1)) {
            require(run.kind != previous) {
                "consecutive ${run.kind} messages violate the role alternation " +
                    "(tool results must directly follow the assistant turn they answer)"
            }
            previous = run.kind
        }
    }

    private fun runElement(
        run: Run,
        resolver: ImageResolver,
    ): JsonElement =
        if (run.toolResults != null) {
            buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    run.toolResults.forEach { result ->
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.toolCallId!!.value)
                                put("content", result.text)
                            },
                        )
                    }
                }
            }
        } else {
            // A non-tool run always carries its single message.
            messageElement(run.single!!, resolver)
        }

    private fun messageElement(
        message: ModelMessage,
        resolver: ImageResolver,
    ): JsonElement {
        val role =
            when (message.role) {
                ModelRole.USER -> "user"
                ModelRole.ASSISTANT -> "assistant"
                else -> error("system/tool handled by the run merge")
            }
        // Exactly one shape per message: plain text, the assistant's tool_use blocks, or
        // the user's text+image blocks (a tool-call step never carries images).
        val isPlain = message.images.isEmpty() && message.toolCalls.isEmpty()
        return buildJsonObject {
            put("role", role)
            if (isPlain) {
                put("content", message.text)
            } else {
                putJsonArray("content") {
                    addTextBlock(message.text)
                    message.toolCalls.forEach { call ->
                        addToolUseBlock(call)
                    }
                    message.images.forEach { image ->
                        addImageBlock(image, resolver)
                    }
                }
            }
        }
    }

    /** A `text` content block (skipped when the text is empty). */
    private fun JsonArrayBuilder.addTextBlock(text: String) {
        if (text.isEmpty()) return
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }

    /** A `tool_use` content block for one assistant tool call (HXA-037 back-fill). */
    private fun JsonArrayBuilder.addToolUseBlock(call: AssistantToolCall) {
        // HXA-037 back-fill: the assistant's tool calls become `tool_use` blocks in the
        // model's original order; the merged tool-result run below answers them by id.
        add(
            buildJsonObject {
                put("type", "tool_use")
                put("id", call.id.value)
                put("name", call.name.value)
                put("input", Json.parseToJsonElement(call.argumentsJson))
            },
        )
    }

    private fun thinkingOf(request: ModelRequest): JsonObject? {
        if (request.reasoning == ReasoningEffort.OFF || request.reasoning == null) return null
        val maxTokens = maxTokensOf(request)
        val budget =
            PREFERRED_THINKING_BUDGET[request.reasoning]!!
                .coerceAtMost(maxTokens - THINKING_MARGIN)
        require(budget >= MIN_THINKING_BUDGET) {
            "max_tokens $maxTokens leaves no room for the ${request.reasoning} thinking " +
                "budget (need at least ${MIN_THINKING_BUDGET + THINKING_MARGIN})"
        }
        return buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", budget)
        }
    }

    /** One wire message: a single USER/ASSISTANT message or a merged TOOL run. */
    private class Run(
        val kind: ModelRole,
        val single: ModelMessage?,
        val toolResults: List<ModelMessage>?,
    ) {
        companion object {
            fun single(message: ModelMessage): Run = Run(message.role, message, null)

            fun tool(messages: List<ModelMessage>): Run = Run(ModelRole.USER, null, messages)
        }
    }

    public companion object {
        /** `max_tokens` sent when [ModelRequest.maxOutputTokens] is unset. */
        const val DEFAULT_MAX_TOKENS = 8_192

        /** The Anthropic API rejects a thinking budget below 1024. */
        const val MIN_THINKING_BUDGET = 1_024L

        /** `max_tokens` must exceed the thinking budget by this margin. */
        const val THINKING_MARGIN = 1_024L

        /** Preferred `thinking.budget_tokens` per effort (clamped to max_tokens). */
        val PREFERRED_THINKING_BUDGET: Map<ReasoningEffort, Long> =
            mapOf(
                ReasoningEffort.LOW to 1_024L,
                ReasoningEffort.MEDIUM to 4_096L,
                ReasoningEffort.HIGH to 16_384L,
            )
    }
}

/** The wire `max_tokens`: the request's bound, else the protocol default. */
private fun maxTokensOf(request: ModelRequest): Long =
    (request.maxOutputTokens ?: AnthropicRequestEncoder.DEFAULT_MAX_TOKENS).toLong()

/** The tool's inputSchema as a JSON object (the wire requires an object, never a schema URL). */
private fun parseSchema(schemaJson: String): JsonElement {
    val element = Json.parseToJsonElement(schemaJson)
    require(element is JsonObject) { "tool inputSchemaJson must be a JSON object" }
    return element
}

/** An `image` content block (base64 or url source). */
private fun JsonArrayBuilder.addImageBlock(
    image: ImageReference,
    resolver: ImageResolver,
) {
    add(
        buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                when (val payload = resolver.resolve(image)) {
                    is ImagePayload.Base64 -> {
                        put("type", "base64")
                        put("media_type", image.mediaType)
                        put("data", payload.value)
                    }

                    is ImagePayload.Url -> {
                        requireValidUrl(payload.value)
                        put("type", "url")
                        put("url", payload.value)
                    }
                }
            }
        },
    )
}

private fun requireValidUrl(url: String) {
        require(url.isNotBlank() && url.none { it.code in 0x00..0x1F || it.code == 0x7F }) {
            "image url is blank or contains a control character"
        }
    }
