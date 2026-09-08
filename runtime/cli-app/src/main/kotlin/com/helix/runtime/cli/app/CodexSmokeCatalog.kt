package com.helix.runtime.cli.app

import com.helix.runtime.cli.app.CodexSubscriptionSmoke.Companion.MAX_CATALOG_BYTES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.BufferedSource

internal object CodexSmokeCatalog {
    fun read(source: BufferedSource): String {
        val bytes =
            try {
                source.readBoundedByteArray(MAX_CATALOG_BYTES)
            } catch (_: IllegalArgumentException) {
                throw CodexSmokeException("models-too-large")
            }
        return models(bytes).firstNotNullOfOrNull(::visibleSlug)
            ?: throw CodexSmokeException("models-empty")
    }

    private fun models(bytes: ByteArray): JsonArray =
        runCatching {
            (Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject)?.get("models") as? JsonArray
        }.getOrNull() ?: throw CodexSmokeException("models-protocol")

    private fun visibleSlug(item: JsonElement): String? {
        val row = item as? JsonObject ?: throw CodexSmokeException("models-protocol")
        val visibility = optionalString(row["visibility"])
        return optionalString(row["slug"])
            ?.takeIf { it.isNotBlank() && it.length <= 128 && visibility !in setOf("hide", "none") }
    }

    private fun optionalString(value: JsonElement?): String? =
        when {
            value == null || value == JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            else -> throw CodexSmokeException("models-protocol")
        }
}
