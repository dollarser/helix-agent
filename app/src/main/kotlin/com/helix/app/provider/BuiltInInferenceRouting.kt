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
    override suspend fun open(request: WireRequest): com.helix.provider.api.wire.WireResponse {
        val normalized =
            try {
                HilexLunaRequestContract.normalize(request)
            } catch (error: com.helix.provider.api.wire.WireRequestRejectedException) {
                throw error
            } catch (_: IllegalArgumentException) {
                throw com.helix.provider.api.wire
                    .WireRequestRejectedException()
            }
        return if (normalized.method == "POST" && normalized.url == BuiltInModel.GENERATION_URL) {
            val body = Json.parseToJsonElement(requireNotNull(normalized.body).toString(Charsets.UTF_8)) as JsonObject
            val model = body["model"] as JsonPrimitive
            require(model.isString)
            require(normalized.headers.keys.none { it.equals("X-Inference-Provider", ignoreCase = true) })
            delegate.open(
                WireRequest(
                    normalized.method,
                    normalized.url,
                    normalized.headers + builtInInferenceHeaders(model.content),
                    normalized.body,
                ),
            )
        } else {
            delegate.open(normalized)
        }
    }
}
