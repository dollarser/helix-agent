package com.helix.app.provider

import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireRequestRejectedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Deployment limits, not claims about the upstream model or other OpenAI endpoints. */
internal object HilexLunaRequestContract {
    fun normalize(request: WireRequest): WireRequest {
        if (request.method != "POST" || request.url !in ENDPOINTS) return request
        val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)) as JsonObject
        return if ((body["model"] as? JsonPrimitive)?.contentOrNull == BuiltInModel.MODEL_ID) {
            val encoded = normalizeBody(body, request.url == BuiltInModel.RESPONSES_URL)
            WireRequest(request.method, request.url, request.headers, encoded)
        } else {
            request
        }
    }

    private fun normalizeBody(
        body: JsonObject,
        responses: Boolean,
    ): ByteArray {
        val fields = body.toMutableMap()
        val outputField = if (responses) "max_output_tokens" else "max_completion_tokens"
        val requested = (body[outputField] ?: body["max_tokens"]) as? JsonPrimitive
        val output = requested?.longOrNull ?: 4096L
        fields.remove("max_tokens")
        fields[outputField] = JsonPrimitive(output.coerceAtMost(16384L))
        val messages = body[if (responses) "input" else "messages"] as? JsonArray
        if (messages?.any { item -> hasMedia(item as? JsonObject) } == true) {
            throw WireRequestRejectedException(WireRequestRejectedException.Reason.UNSUPPORTED_MEDIA)
        }
        if (!responses && hasTools(body, messages)) {
            fields["reasoning_effort"] = JsonPrimitive("none")
        }
        val encoded = JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
        // Applies the documented gateway framing formula to the client wire payload.
        // Server normalization can change bytes; server admission remains authoritative.
        if (encoded.size.toLong() + 4096L + (messages?.size ?: 1) * 64L > 250000L) {
            throw WireRequestRejectedException(WireRequestRejectedException.Reason.INPUT_TOO_LARGE)
        }
        return encoded
    }

    private fun hasTools(
        body: JsonObject,
        messages: JsonArray?,
    ): Boolean =
        (body["tools"] as? JsonArray)?.isNotEmpty() == true ||
            messages?.any { item ->
                val message = item as? JsonObject
                (message?.get("role") as? JsonPrimitive)?.contentOrNull == "tool" ||
                    (message?.get("tool_calls") as? JsonArray)?.isNotEmpty() == true
            } == true

    private fun hasMedia(message: JsonObject?): Boolean =
        (message?.get("content") as? JsonArray)?.any { item ->
            val type = ((item as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            type !in setOf("text", "input_text", "output_text")
        } == true

    private val ENDPOINTS = setOf(BuiltInModel.GENERATION_URL, BuiltInModel.RESPONSES_URL)
}
