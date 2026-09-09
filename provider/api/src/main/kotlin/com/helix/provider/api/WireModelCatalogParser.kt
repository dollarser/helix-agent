package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object WireModelCatalogParser {
    /**
     * Strict parse of the OpenAI-compatible `{"data":[{"id":...}]}` list body.
     * Each of the four vendor contract violations (not JSON / not an object /
     * no data array / entry without an id string) and the bound check is a
     * distinct PROTOCOL failure — all are folded into one result here.
     */
    fun parseModelIds(body: String): ModelCatalogResult {
        val parsed = parseListObject(body)
        val collected = ArrayList<String>()
        var problem: String? = (parsed as? ModelListParse.Problem)?.detail
        if (parsed is ModelListParse.Ok) {
            for (entry in parsed.data) {
                val text = (entry as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }
                if (text == null) {
                    problem = "model entry has no id string"
                    break
                }
                collected += text
            }
        }
        return when {
            problem != null -> {
                ModelCatalogResult.Failed(
                    ModelErrorCode.PROTOCOL,
                    boundedDetail(problem),
                    retryable = false,
                )
            }

            else -> {
                try {
                    ModelCatalogResult.Listed(collected)
                } catch (e: IllegalArgumentException) {
                    ModelCatalogResult.Failed(
                        ModelErrorCode.PROTOCOL,
                        boundedDetail("model list violates bounds: ${e.message}"),
                        retryable = false,
                    )
                }
            }
        }
    }
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
