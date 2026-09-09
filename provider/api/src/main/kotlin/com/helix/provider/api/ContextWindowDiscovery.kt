package com.helix.provider.api

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Optional metadata failure is unknown, not a model capability or connection-test failure. */
internal object ContextWindowDiscovery {
    suspend fun read(
        wire: WireClient,
        headers: Map<String, String>,
        catalogUrl: String,
        serverUrl: String?,
        model: String,
    ): Long? {
        val catalog = fetch(wire, headers, catalogUrl)
        val rows = catalog?.get("data") as? JsonArray
        val entry =
            rows?.filterIsInstance<JsonObject>()?.singleOrNull {
                (it["id"] as? JsonPrimitive)?.content == model
            }
        val declared = entry?.let(::window)
        return declared ?: if (entry != null && rows.size == 1 && serverUrl != null) {
            fetch(wire, headers, serverUrl)?.let(::window)
        } else {
            null
        }
    }

    internal fun window(obj: JsonObject): Long? =
        listOf("context_length", "context_window", "max_model_len").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1024..1_000_000 }
        }

    @Suppress("SwallowedException") // Optional metadata only; cancellation propagates.
    private suspend fun fetch(wire: WireClient, headers: Map<String, String>, url: String): JsonObject? =
        try {
            val response = wire.open(WireRequest("GET", url, headers, null))
            try {
                if (response.status in
                    200..299
                ) {
                    Json.parseToJsonElement(response.body.bytes().decodeToString()) as? JsonObject
                } else {
                    null
                }
            } finally {
                response.body.close()
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
