package com.helix.provider.openai.responses

import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.RequestEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An image payload resolved from an [ImageReference] (the contract never carries
 * inline image bytes, HXA-021). The runtime/transport layer (HXA-025) resolves
 * the opaque content-store reference; the adapter only encodes it.
 */
public sealed interface ImagePayload {
    /** Standard base64; the encoder builds the vendor data URL. */
    public data class Base64(
        public val value: String,
    ) : ImagePayload

    /** Fully qualified public URL. */
    public data class Url(
        public val value: String,
    ) : ImagePayload
}

/** Resolves contract image references into vendor payloads. */
public fun interface ImageResolver {
    public fun resolve(image: ImageReference): ImagePayload
}

/**
 * Encodes a [ModelRequest] into the OpenAI Responses API request body
 * (`POST /v1/responses`, `stream: true`), doc 10 section 2.1.
 *
 * Stateless by design: every request carries the full `input` conversation
 * (no `previous_response_id` / stateful conversation — doc 10 section 2.5:
 * Ollama's Responses compatibility does not cover the stateful fields, and
 * stateless input is the portable path).
 *
 * Mapping (fail closed at construction of the JSON):
 * - `SYSTEM`/`USER`/`ASSISTANT` → `message` items; user/system text parts are
 *   `input_text`, assistant text is `output_text`;
 * - `TOOL` → `function_call_output` items keyed by the call id;
 * - user images → `input_image` content parts with a data URL (base64) or a
 *   public URL, resolved through the injected [ImageResolver];
 * - tools → `function` tools; the canonical [com.helix.core.model.ModelToolSchema.inputSchemaJson]
 *   object is embedded as `parameters`;
 * - sampling: `temperature`/`max_output_tokens`/`seed`/`stop` when set;
 *   `reasoning.effort` only when not [ReasoningEffort.OFF].
 *
 * Secrets never appear here: the transport adds the `Authorization` header from
 * the SecretStore (HXA-020/HXA-025), never the body.
 */
public class ResponsesRequestEncoder(
    private val imageResolver: ImageResolver,
) : RequestEncoder {
    public override fun encode(request: ModelRequest): String {
        val root =
            buildJsonObject {
                put("model", request.model)
                put("stream", true)
                putJsonArray("input") { request.messages.forEach { add(inputItem(it)) } }
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", tool.name.value)
                                    put("description", tool.description)
                                    put("parameters", parseSchema(tool.inputSchemaJson))
                                },
                            )
                        }
                    }
                }
                request.temperature?.let { put("temperature", it) }
                request.maxOutputTokens?.let { put("max_output_tokens", it) }
                request.seed?.let { put("seed", it) }
                if (request.stopSequences.isNotEmpty()) {
                    putJsonArray("stop") { request.stopSequences.forEach { add(JsonPrimitive(it)) } }
                }
                if (request.reasoning != ReasoningEffort.OFF) {
                    putJsonObject("reasoning") {
                        put("effort", request.reasoning.name.lowercase())
                    }
                }
            }
        // JsonElement.toString() renders the tree as compact, valid JSON.
        return root.toString()
    }

    private fun inputItem(message: ModelMessage): JsonElement =
        when (message.role) {
            ModelRole.TOOL -> {
                val callId = message.toolCallId
                require(callId != null) { "tool result message must carry the call id" }
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId.value)
                    put("output", message.text)
                }
            }

            ModelRole.SYSTEM,
            ModelRole.USER,
            ModelRole.ASSISTANT,
            -> {
                buildJsonObject {
                    put("type", "message")
                    put("role", message.role.name.lowercase())
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", textPartType(message.role))
                                put("text", message.text)
                            },
                        )
                        if (message.role == ModelRole.USER) {
                            message.images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", imageUrlOf(image))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun imageUrlOf(image: ImageReference): String =
        when (val payload = imageResolver.resolve(image)) {
            is ImagePayload.Base64 -> {
                require(payload.value.isNotBlank()) { "image base64 must not be blank" }
                "data:${image.mediaType};base64,${payload.value}"
            }

            is ImagePayload.Url -> {
                require(payload.value.isNotBlank()) { "image url must not be blank" }
                require(payload.value.none { c -> c.code in 0x00..0x1F || c.code in 0x7F..0x9F }) {
                    "image url contains a control character"
                }
                payload.value
            }
        }

    private fun textPartType(role: ModelRole): String = if (role == ModelRole.ASSISTANT) "output_text" else "input_text"

    private fun parseSchema(schemaJson: String): JsonElement {
        val element = Json.parseToJsonElement(schemaJson)
        require(element is JsonObject) { "tool input schema must be a JSON object" }
        return element
    }
}
