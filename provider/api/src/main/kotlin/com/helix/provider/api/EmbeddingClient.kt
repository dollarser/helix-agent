package com.helix.provider.api

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.math.sqrt

/** OpenAI embedding transport only; discovery does not establish model semantics or grant egress. */
class EmbeddingClient(
    private val wire: WireClient,
    private val endpoint: String,
) {
    suspend fun embed(
        model: String,
        inputs: List<String>,
        headers: Map<String, String> = emptyMap(),
    ): List<FloatArray> =
        withTimeout(30_000) {
            require(model.isNotBlank() && model.length <= 200)
            require(inputs.size in 1..16 && inputs.all { it.isNotBlank() && it.length <= 8192 })
            val request =
                buildJsonObject {
                    put("model", model)
                    put("input", JsonArray(inputs.map(::JsonPrimitive)))
                    put("encoding_format", "float")
                }
            val response =
                wire.open(
                    WireRequest(
                        "POST",
                        endpoint,
                        headers + ("Content-Type" to "application/json"),
                        request.toString().toByteArray(),
                    ),
                )
            try {
                if (response.status !in 200..299) throw IOException("Embedding HTTP ${response.status}")
                val bytes = java.io.ByteArrayOutputStream()
                response.body.forEachChunk { chunk ->
                    require(bytes.size() + chunk.size <= 4 * 1024 * 1024) { "Embedding response exceeds limit" }
                    bytes.write(chunk)
                    true
                }
                parse(bytes.toString(Charsets.UTF_8.name()), inputs.size)
            } finally {
                response.body.close()
            }
        }

    companion object {
        /** Validate response ordering, dimensions and finite nonzero vectors before publishing anything. */
        fun parse(
            body: String,
            count: Int,
        ): List<FloatArray> {
            require(count in 1..16 && body.length <= 4 * 1024 * 1024)
            val rows = Json.parseToJsonElement(body).jsonObject.getValue("data") as JsonArray
            require(rows.size == count)
            val vectors = arrayOfNulls<FloatArray>(count)
            var dimension = 0
            rows.forEach { row ->
                val value = row.jsonObject
                val index =
                    value
                        .getValue("index")
                        .jsonPrimitive
                        .also { require(!it.isString) }
                        .int
                require(index in vectors.indices && vectors[index] == null)
                val array = value.getValue("embedding") as JsonArray
                require(array.size in 1..8192 && (dimension == 0 || dimension == array.size))
                dimension = array.size
                val numbers =
                    array.map {
                        it.jsonPrimitive
                            .also { number ->
                                require(!number.isString)
                            }.double
                            .also { number -> require(number.isFinite()) }
                    }
                val norm = sqrt(numbers.sumOf { it * it })
                require(norm.isFinite() && norm > 0)
                vectors[index] = FloatArray(dimension) { (numbers[it] / norm).toFloat() }
            }
            return vectors.map { requireNotNull(it) }
        }
    }
}
