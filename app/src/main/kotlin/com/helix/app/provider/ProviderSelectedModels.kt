package com.helix.app.provider

import com.helix.app.internal.LineStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An explicit empty selection is different from a source which has never been configured. */
data class ProviderModelSelection(
    val models: List<String> = emptyList(),
    val configured: Boolean = false,
    val customModels: List<String> = emptyList(),
) {
    fun toggle(
        model: String,
        enabled: Boolean,
    ): ProviderModelSelection {
        val next = if (enabled) (models + model).distinct() else models - model
        return copy(models = next, configured = true)
    }

    fun addCustom(model: String): ProviderModelSelection =
        toggle(model, true).copy(customModels = (customModels + model).distinct())

    fun moveFirst(model: String): ProviderModelSelection {
        require(model in models)
        return copy(models = listOf(model) + (models - model), configured = true)
    }

    init {
        ProviderSelectedModels.validate(models)
        ProviderSelectedModels.validate(customModels)
        require(models.distinct() == models)
        require(customModels.distinct() == customModels)
        require(configured || (models.isEmpty() && customModels.isEmpty()))
    }
}

/** User choices are not capability evidence and survive connection-test invalidation. */
class ProviderSelectedModels(
    private val store: LineStore,
) {
    fun read(id: String): List<String> = selection(id).models

    fun selection(id: String): ProviderModelSelection =
        synchronized(LOCK) {
            val lines = store.lines("provider-model-selection-v2-$id")
            if (lines.isEmpty()) {
                // Preserve existing explicit choices only; an absent/empty old list never means all models.
                val explicit = store.lines("provider-selected-models-$id")
                validate(explicit)
                return@synchronized if (explicit.isEmpty()) {
                    ProviderModelSelection()
                } else {
                    ProviderModelSelection(explicit.distinct(), configured = true)
                }
            }
            require(lines.size == 1 && lines.single().length <= 1024 * 1024)
            val parsed = Json.parseToJsonElement(lines.single())
            val document = requireNotNull(parsed as? kotlinx.serialization.json.JsonObject)
            // Preserve explicit selections from older records, but discard their retired default.
            require(document.keys == setOf("models", "custom") || document.keys == setOf("models", "default", "custom"))
            val models =
                requireNotNull(document["models"] as? JsonArray).map {
                    require(it.jsonPrimitive.isString)
                    it.jsonPrimitive.content
                }
            val custom =
                requireNotNull(document["custom"] as? JsonArray).map {
                    require(it.jsonPrimitive.isString)
                    it.jsonPrimitive.content
                }
            ProviderModelSelection(models, configured = true, customModels = custom)
        }

    fun write(
        id: String,
        models: List<String>,
    ) {
        validate(models)
        save(id, ProviderModelSelection(models.distinct(), configured = true))
    }

    /** One preference document, with optional stale-dialog protection. Never changes capability evidence. */
    fun save(
        id: String,
        value: ProviderModelSelection,
        expected: ProviderModelSelection? = null,
    ) {
        require(value.configured)
        synchronized(LOCK) {
            check(expected == null || selection(id) == expected) { "Model selection changed; reload before saving" }
            store.setLines(
                "provider-model-selection-v2-$id",
                listOf(
                    buildJsonObject {
                        put("models", JsonArray(value.models.map(::JsonPrimitive)))
                        put("custom", JsonArray(value.customModels.map(::JsonPrimitive)))
                    }.toString(),
                ),
            )
        }
    }

    fun clear(id: String) =
        synchronized(LOCK) {
            store.setLines("provider-model-selection-v2-$id", emptyList())
            store.setLines("provider-selected-models-$id", emptyList())
        }

    companion object {
        private val LOCK = Any()

        fun validate(models: List<String>) {
            require(models.size <= 1024)
            require(
                models.all { model ->
                    model.isNotBlank() && model.length <= 256 && model.none { it <= ' ' || it == '\u007f' }
                },
            )
        }
    }
}
