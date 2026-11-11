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
    /** Derive response limits from exact endpoint/model facts, never persisted user policy. */
    fun contextSettings(
        providerId: String,
        endpoint: String,
        model: String,
        settings: ProviderContextSettingsStore,
    ): com.helix.provider.api.ProviderContextSettings =
        settings
            .read(providerId, endpoint, model)
            .copy(serverOutputLimit = read(providerId, endpoint)[model]?.maxOutputTokens)

    fun write(
        providerId: String,
        endpoint: String,
        models: Map<String, ModelMetadata>,
    ) {
        require(models.size <= 1024)
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

    /** A successful directory refresh cannot turn an earlier explicit task into unknown chat. */
    fun catalog(
        providerId: String,
        endpoint: String,
        catalog: com.helix.provider.api.ModelCatalogResult.Listed,
    ) {
        val previous = read(providerId, endpoint)
        write(
            providerId,
            endpoint,
            catalog.models.associateWith { id ->
                val old = previous[id] ?: ModelMetadata()
                old.copy(
                    purposes = catalog.purposes[id].orEmpty().ifEmpty { old.purposes },
                    maxOutputTokens = catalog.outputLimits[id],
                )
            },
        )
    }

    fun merge(
        providerId: String,
        endpoint: String,
        models: Map<String, ModelMetadata>,
    ) {
        val previous = read(providerId, endpoint)
        write(
            providerId,
            endpoint,
            previous +
                models.mapValues { (id, value) ->
                    val old = previous[id] ?: ModelMetadata()
                    value.copy(purposes = value.purposes.ifEmpty { old.purposes })
                },
        )
    }

    fun read(
        providerId: String,
        endpoint: String,
    ): Map<String, ModelMetadata> =
        runCatching {
            val text = store.lines(key(providerId, endpoint)).singleOrNull() ?: return emptyMap()
            require(text.encodeToByteArray().size <= MAX_BYTES)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.size <= 1024)
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
            put("output", metadata.maxOutputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("purposes", JsonArray(metadata.purposes.map { JsonPrimitive(it.name) }))
        }

    private fun decode(value: JsonElement): ModelMetadata {
        val item = value.jsonObject
        require(item.keys == setOf("efforts", "vision", "context", "purposes", "output"))
        val efforts = item.getValue("efforts").takeUnless { it == JsonNull }
        val vision = literal(item.getValue("vision"))
        val context = literal(item.getValue("context"))
        val output = literal(item.getValue("output"))
        return ModelMetadata(
            reasoningEfforts =
                efforts?.let { list ->
                    (list as JsonArray).map { ReasoningEffort.valueOf(it.jsonPrimitive.content) }
                },
            vision = vision?.let { requireNotNull(it.booleanOrNull) },
            contextWindow = context?.let { requireNotNull(it.longOrNull) },
            maxOutputTokens = output?.let { requireNotNull(it.longOrNull) },
            purposes =
                (item.getValue("purposes") as JsonArray)
                    .map {
                        com.helix.provider.api.ModelPurpose
                            .valueOf(it.jsonPrimitive.content)
                    }.toSet(),
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
        const val MAX_BYTES = 512 * 1024
    }
}
