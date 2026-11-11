package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object WireModelCatalogParser {
    /**
     * Strict parse of the OpenAI-compatible `{"data":[{"id":...}]}` list body.
     * Each of the four vendor contract violations (not JSON / not an object /
     * no data array / entry without an id string) and the bound check is a
     * distinct PROTOCOL failure — all are folded into one result here.
     */
    fun parseModelIds(
        body: String,
        outputModalitiesOnly: Boolean = false,
    ): ModelCatalogResult {
        val parsed = parseListObject(body)
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if ((root?.get("has_more") as? JsonPrimitive)?.content == "true" ||
            (root?.get("nextPageToken") as? JsonPrimitive)?.content?.isNotEmpty() == true
        ) {
            return ModelCatalogResult.Failed(
                ModelErrorCode.PROTOCOL,
                "Paginated directory requires a supported provider adapter",
                false,
            )
        }
        return when (parsed) {
            is ModelListParse.Problem -> {
                ModelCatalogResult.Failed(ModelErrorCode.PROTOCOL, boundedDetail(parsed.detail), false)
            }

            is ModelListParse.Ok -> {
                parseEntries(parsed.data, outputModalitiesOnly)
            }
        }
    }

    private fun parseEntries(
        entries: JsonArray,
        outputModalitiesOnly: Boolean,
    ): ModelCatalogResult =
        try {
            val collected = ArrayList<String>()
            val purposes = linkedMapOf<String, Set<ModelPurpose>>()
            val outputLimits = linkedMapOf<String, Long>()
            for (value in entries) {
                val entry = requireNotNull(value as? JsonObject) { "model entry is not an object" }
                val id = entry["id"] as? JsonPrimitive
                require(id != null && id.isString) { "model entry has no id string" }
                val text = id.content
                declaredOutputLimit(entry)?.let { outputLimits[text] = it }
                collected += text
                purposes[text] =
                    if (outputModalitiesOnly) ModelPurposes.outputModalities(entry) else ModelPurposes.parse(entry)
            }
            ModelCatalogResult.Listed(collected, purposes.filterValues { it.isNotEmpty() }, outputLimits)
        } catch (error: IllegalArgumentException) {
            ModelCatalogResult.Failed(
                ModelErrorCode.PROTOCOL,
                boundedDetail("model list violates bounds: ${error.message}"),
                false,
            )
        }

    /** Only explicit single-response caps, never context capacity or inferred model defaults. */
    private fun declaredOutputLimit(entry: JsonObject): Long? =
        listOf("max_output_tokens", "max_completion_tokens")
            .mapNotNull { key ->
                val value = entry[key]?.takeUnless { it == JsonNull } ?: return@mapNotNull null
                val number = value as? JsonPrimitive
                require(number != null && !number.isString)
                requireNotNull(number.longOrNull).also {
                    require(it in 1..ProviderCapabilities.MAX_CONTEXT_BOUND)
                }
            }.minOrNull()
}

private sealed interface ModelListParse {
    data class Ok(
        val data: JsonArray,
    ) : ModelListParse

    data class Problem(
        val detail: String,
    ) : ModelListParse
}

/**
 * Each return is a distinct vendor contract violation (not JSON / not an
 * object / no data array), folded into the [ModelListParse.Problem] class.
 */
@Suppress("ReturnCount") // three fail-closed returns, one per vendor violation
private fun parseListObject(body: String): ModelListParse {
    val element =
        try {
            Json.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {
            return ModelListParse.Problem("models body is not JSON: ${e::class.simpleName}")
        }
    val obj =
        element as? JsonObject
            ?: return ModelListParse.Problem("models body is not a JSON object")
    val data =
        obj["data"] as? JsonArray
            ?: return ModelListParse.Problem("models body has no data array")
    return ModelListParse.Ok(data)
}
