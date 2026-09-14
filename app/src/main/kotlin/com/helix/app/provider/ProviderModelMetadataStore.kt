package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** Public metadata only, scoped to a provider and its normalized endpoint. Old installs start empty. */
class ProviderModelMetadataStore(
    private val store: LineStore,
) {
    fun write(
        providerId: String,
        endpoint: String,
        models: Map<String, ModelMetadata>,
    ) {
        require(models.size <= 128)
        val document =
            buildJsonObject {
                models.forEach { (id, metadata) ->
                    require(id.isNotBlank() && id.length <= 256)
                    put(id, encode(metadata))
                }
            }.toString()
        require(document.encodeToByteArray().size <= MAX_BYTES)
        store.setLines(key(providerId, endpoint), listOf(document))
    }

    fun read(
        providerId: String,
        endpoint: String,
    ): Map<String, ModelMetadata> =
        runCatching {
            val text = store.lines(key(providerId, endpoint)).singleOrNull() ?: return emptyMap()
            require(text.encodeToByteArray().size <= MAX_BYTES)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.size <= 128)
            root.mapValues { (id, value) ->
                require(id.isNotBlank() && id.length <= 256)
                decode(value)
            }
        }.getOrDefault(emptyMap())

    private fun encode(metadata: ModelMetadata): JsonObject =
        buildJsonObject {
            val efforts = metadata.reasoningEfforts?.map { JsonPrimitive(it.name) }
            put("efforts", efforts?.let(::JsonArray) ?: JsonNull)
            put("vision", metadata.vision?.let(::JsonPrimitive) ?: JsonNull)
            put("context", metadata.contextWindow?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun decode(value: JsonElement): ModelMetadata {
        val item = value.jsonObject
        require(item.keys == setOf("efforts", "vision", "context"))
        val efforts = item.getValue("efforts").takeUnless { it == JsonNull }
        val vision = literal(item.getValue("vision"))
        val context = literal(item.getValue("context"))
        return ModelMetadata(
            reasoningEfforts =
                efforts?.let { list ->
                    (list as JsonArray).map { ReasoningEffort.valueOf(it.jsonPrimitive.content) }
                },
            vision = vision?.let { requireNotNull(it.booleanOrNull) },
            contextWindow = context?.let { requireNotNull(it.longOrNull) },
        )
    }

    private fun literal(value: JsonElement): JsonPrimitive? =
        value.takeUnless { it == JsonNull }?.jsonPrimitive?.also {
            require(!it.isString)
        }

    private fun key(
        providerId: String,
        endpoint: String,
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${providerId.length}:$providerId$endpoint".toByteArray(Charsets.UTF_8))
        return "model-metadata-v1-" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_BYTES = 128 * 1024
    }
}
