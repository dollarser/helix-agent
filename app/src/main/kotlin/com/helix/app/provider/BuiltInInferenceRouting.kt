package com.helix.app.provider

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit deployment contract for the known self-hosted model, never a failure-driven fallback. */
internal fun builtInInferenceHeaders(model: String): Map<String, String> =
    if (model == BuiltInModel.QWEN_MODEL_ID) mapOf("X-Inference-Provider" to "local") else emptyMap()

/** Uses this encoded invocation's model, not a later selection or the provider row's default. */
internal class BuiltInInferenceRouting(
    private val delegate: WireClient,
) : WireClient {
    override suspend fun open(request: WireRequest) =
        if (request.method == "POST" && request.url == BuiltInModel.GENERATION_URL) {
            val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)) as JsonObject
            val model = body["model"] as JsonPrimitive
            require(model.isString)
            require(request.headers.keys.none { it.equals("X-Inference-Provider", ignoreCase = true) })
            delegate.open(
                WireRequest(
                    request.method,
                    request.url,
                    request.headers + builtInInferenceHeaders(model.content),
                    request.body,
                ),
            )
        } else {
            delegate.open(request)
        }
}
