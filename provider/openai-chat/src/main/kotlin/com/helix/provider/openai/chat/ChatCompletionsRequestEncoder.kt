package com.helix.provider.openai.chat

import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.RequestEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Resolved form of an [ImageReference] for the Chat Completions request body
 * (HXA-023, independent implementation of the HXA-022 Responses resolver
 * boundary — the adapters share no code by design). The transport layer
 * (HXA-025) wires the concrete resolver backed by the content store.
 */
public sealed interface ImagePayload {
    /** Base64-encoded bytes; encoded as a `data:` URL by the encoder. */
    public data class Base64(
        public val value: String,
    ) : ImagePayload

    /** Public HTTPS URL of the image. */
    public data class Url(
        public val value: String,
    ) : ImagePayload
}

/**
 * Resolves opaque [ImageReference]s into concrete payloads. Fail-closed: any
 * miss (content store failure, unsupported media) must throw
 * [IllegalArgumentException]; the encoder never substitutes a placeholder.
 */
public fun interface ImageResolver {
    public fun resolve(image: ImageReference): ImagePayload
}

/**
 * Encodes a [ModelRequest] into an OpenAI Chat Completions request body
 * (HXA-023, doc 02 section 6.2). Independent implementation: the request
 * shape is the Chat Completions one — `messages` with role-based content,
 * `tools` nested under `function`, `max_tokens` (not `max_output_tokens`),
 * and `stream_options.include_usage` so the stream carries the final usage
 * chunk the decoder maps to [com.helix.core.model.ModelEvent.Usage].
 *
 * Stateless by design (doc 10 section 2.5): the full conversation is sent on
 * every request; there is no `previous_response_id`-style session pointer.
 * Secrets never appear in the body (the transport adds Authorization, HXA-025).
 *
 * Role mapping:
 * - SYSTEM/USER/ASSISTANT → `{"role": ...}` with a plain string content, or a
 *   content array for USER messages carrying images (`text` part first, then
 *   one `image_url` part per image);
 * - TOOL → `{"role": "tool", "tool_call_id": ..., "content": ...}`;
 * - an assistant message's tool calls ARE re-encoded (HXA-037 back-fill): a
 *   tool-call step re-carries `tool_calls` in the model's original order
 *   (id / function.name / raw arguments); the following `tool` messages key
 *   by these ids — without this the history of any tool-using turn would be
 *   unrepresentable to the model.
 */
public class ChatCompletionsRequestEncoder(
    public val imageResolver: ImageResolver,
) : RequestEncoder {
    private val json = Json { ignoreUnknownKeys = true }

    /** Encodes the request as a compact JSON body string. */
    public override fun encode(request: ModelRequest): String =
        buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("stream", JsonPrimitive(true))
            put(
                "stream_options",
                buildJsonObject { put("include_usage", JsonPrimitive(true)) },
            )
            putJsonArray("messages") {
                request.messages.forEach { add(messageElement(it)) }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(it.name.value))
                                        put("description", JsonPrimitive(it.description))
                                        put("parameters", parseSchema(it.inputSchemaJson))
                                    },
                                )
                            },
                        )
                    }
                }
            }
            request.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            request.seed?.let { put("seed", JsonPrimitive(it)) }
            if (request.stopSequences.isNotEmpty()) {
                putJsonArray("stop") { request.stopSequences.forEach { add(JsonPrimitive(it)) } }
            }
            if (request.reasoning != ReasoningEffort.OFF) {
                put(
                    "reasoning_effort",
                    JsonPrimitive(request.reasoning.name.lowercase()),
                )
            }
        }.toString()

    private fun messageElement(message: ModelMessage): JsonElement =
        when (message.role) {
            ModelRole.TOOL -> {
                buildJsonObject {
                    put("role", JsonPrimitive("tool"))
                    put("tool_call_id", JsonPrimitive(message.toolCallId!!.value))
                    put("content", JsonPrimitive(message.text))
                }
            }

            ModelRole.SYSTEM, ModelRole.USER, ModelRole.ASSISTANT -> {
                val content =
                    if (message.images.isEmpty()) {
                        // An assistant tool-call step may be textless (its content IS the
                        // calls): the wire accepts `content: null` then.
                        if (message.text.isEmpty()) JsonNull else JsonPrimitive(message.text)
                    } else {
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(message.text))
                                },
                            )
                            message.images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("image_url"))
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put("url", JsonPrimitive(imageUrlOf(image)))
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                buildJsonObject {
                    put("role", JsonPrimitive(message.role.name.lowercase()))
                    put("content", content)
                    // HXA-037 back-fill: re-carry the assistant's tool calls in the model's
                    // original order; the following tool messages key by these ids.
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            message.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(call.id.value))
                                        put("type", JsonPrimitive("function"))
                                        putJsonObject("function") {
                                            put("name", JsonPrimitive(call.name.value))
                                            put("arguments", JsonPrimitive(call.argumentsJson))
                                        }
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
                "data:${image.mediaType};base64,${payload.value}"
            }

            is ImagePayload.Url -> {
                require(payload.value.isNotBlank()) { "image url is blank" }
                require(payload.value.none { it.isISOControl() }) {
                    "image url contains a control character"
                }
                payload.value
            }
        }

    private fun parseSchema(schemaJson: String): JsonElement {
        val element = json.parseToJsonElement(schemaJson)
        require(element is JsonObject) { "tool schema is not a JSON object" }
        return element
    }
}
