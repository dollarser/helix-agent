package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilityProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Directory evidence only. A listed model is not a promise about inference or model capabilities. */
data class BuiltInModelCatalog(
    val checkedAt: Long = 0,
    val models: List<String> = emptyList(),
    val failure: ModelErrorCode? = null,
) {
    fun fresh(now: Long): Boolean =
        checkedAt > 0 && now >= checkedAt && now - checkedAt < if (failure == null) SUCCESS_TTL else FAILURE_TTL

    companion object {
        const val SUCCESS_TTL = 30 * 60_000L
        const val FAILURE_TTL = 60_000L
    }
}

internal class BuiltInModelCatalogStore(
    private val backing: LineStore,
) {
    fun read(): BuiltInModelCatalog {
        val rows = backing.lines(KEY)
        if (rows.size != 5 || rows.take(2) != identity) return BuiltInModelCatalog()
        return try {
            val at = rows[2].toLong().also { require(it > 0) }
            val models = requireNotNull(parseModels(rows[3]))
            val failure = if (rows[4] == "LISTED") null else ModelErrorCode.valueOf(rows[4])
            BuiltInModelCatalog(at, models, failure)
        } catch (_: IllegalArgumentException) {
            BuiltInModelCatalog()
        }
    }

    fun save(value: BuiltInModelCatalog) {
        require(value.checkedAt > 0)
        val models = JsonArray(CapabilityProbe.normalizeModelIds(value.models).map(::JsonPrimitive))
        backing.setLines(
            KEY,
            identity + listOf(value.checkedAt.toString(), models.toString(), value.failure?.name ?: "LISTED"),
        )
    }

    private fun parseModels(raw: String): List<String>? =
        try {
            val array = requireNotNull(Json.parseToJsonElement(raw) as? JsonArray)
            val models =
                array.map { entry ->
                    val primitive = requireNotNull(entry as? JsonPrimitive)
                    require(primitive.isString)
                    primitive.content
                }
            if (models.isNotEmpty()) {
                com.helix.provider.api.ModelCatalogResult
                    .Listed(models)
            }
            CapabilityProbe.normalizeModelIds(models)
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val KEY = "builtin_model_catalog"
        val identity = listOf(BuiltInModel.CATALOG_URL, BuiltInModel.CLIENT_ID)
    }
}
