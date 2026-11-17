package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ModelErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Directory evidence only. A listed model is not a promise about inference or model capabilities. */
data class BuiltInModelCatalog(
    val checkedAt: Long = 0,
    val models: List<String> = emptyList(),
    val failure: ModelErrorCode? = null,
    val purposes: Map<String, Set<com.helix.provider.api.ModelPurpose>> = emptyMap(),
    val partial: Boolean = false,
) {
    fun fresh(now: Long): Boolean =
        checkedAt > 0 && now >= checkedAt &&
            now - checkedAt < if (failure == null && !partial) SUCCESS_TTL else FAILURE_TTL

    companion object {
        const val SUCCESS_TTL = 30 * 60_000L
        const val FAILURE_TTL = 60_000L
    }
}

internal class BuiltInModelCatalogStore(
    private val backing: LineStore,
    private val accountIdentity: () -> String = { "signed-out" },
) {
    fun accountAvailable() = accountIdentity() != "signed-out"

    fun identity() = listOf(BuiltInModel.CATALOG_URL, "account-v1:" + accountIdentity())

    fun read(): BuiltInModelCatalog {
        val rows = backing.lines(KEY)
        if (!accountAvailable() || rows.size !in 6..7 || rows.take(2) != identity()) return BuiltInModelCatalog()
        return try {
            val at = rows[2].toLong().also { require(it > 0) }
            val models = requireNotNull(parseModels(rows[3]))
            val failure = if (rows[4] == "LISTED") null else ModelErrorCode.valueOf(rows[4])
            val partial = rows.getOrNull(6) == "PARTIAL"
            BuiltInModelCatalog(
                at,
                models,
                failure,
                Json
                    .parseToJsonElement(rows[5])
                    .jsonObject
                    .mapValues { (_, value) ->
                        (value as JsonArray)
                            .map {
                                com.helix.provider.api.ModelPurpose.valueOf(
                                    it.jsonPrimitive.content,
                                )
                            }.toSet()
                    }.also { require(it.size <= 1024 && (partial || it.keys.all { id -> id in models })) },
                partial = partial,
            )
        } catch (_: IllegalArgumentException) {
            BuiltInModelCatalog()
        }
    }

    fun save(
        value: BuiltInModelCatalog,
        expectedIdentity: List<String> = identity(),
    ) {
        require(value.checkedAt > 0)
        val models =
            JsonArray(
                com.helix.provider.api.ModelCatalogResult
                    .Listed(
                        value.models,
                        value.purposes.filterKeys { it in value.models },
                    ).models
                    .map(::JsonPrimitive),
            )
        backing.setLines(
            KEY,
            expectedIdentity +
                listOf(
                    value.checkedAt.toString(),
                    models.toString(),
                    value.failure?.name ?: "LISTED",
                    buildJsonObject {
                        value.purposes.forEach { (id, purposes) ->
                            put(id, JsonArray(purposes.map { JsonPrimitive(it.name) }))
                        }
                    }.toString(),
                    if (value.partial) "PARTIAL" else "COMPLETE",
                ),
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
            models
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val KEY = "builtin_model_catalog"
    }
}
