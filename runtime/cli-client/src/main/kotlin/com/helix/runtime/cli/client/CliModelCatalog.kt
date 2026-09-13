package com.helix.runtime.cli.client

import com.helix.core.model.ModelErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Credential-free, bounded metadata. Model support does not grant tool or egress authority. */
data class CliModelInfo(
    val id: String,
    val vision: Boolean?,
    val reasoningEfforts: List<String>?,
    val contextWindow: Long?,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
        require(reasoningEfforts == null || (reasoningEfforts.size <= 16 && reasoningEfforts.all { validEffort(it) }))
        require(reasoningEfforts == null || reasoningEfforts.distinct().size == reasoningEfforts.size)
        require(contextWindow == null || contextWindow in 1..10_000_000L)
    }

    companion object {
        fun validEffort(value: String): Boolean = value.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && value != "off"
    }
}

sealed interface CliModelCatalog {
    data class Listed(
        val models: List<CliModelInfo>,
    ) : CliModelCatalog

    data class Failed(
        val code: ModelErrorCode,
        val retryable: Boolean,
    ) : CliModelCatalog
}

object CliModelCatalogCodec {
    const val MAX_BYTES = 64 * 1024
    const val MAX_MODELS = 128

    fun encode(result: CliModelCatalog): String =
        buildJsonObject {
            when (result) {
                is CliModelCatalog.Listed -> {
                    require(result.models.size in 1..MAX_MODELS)
                    put(
                        "models",
                        buildJsonArray {
                            result.models.forEach { model ->
                                add(
                                    buildJsonObject {
                                        put("id", model.id)
                                        put("vision", model.vision?.let(::JsonPrimitive) ?: JsonNull)
                                        val reasoning = model.reasoningEfforts?.map(::JsonPrimitive)
                                        put("reasoning", reasoning?.let { JsonArray(it) } ?: JsonNull)
                                        put("context", model.contextWindow?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                                )
                            }
                        },
                    )
                }

                is CliModelCatalog.Failed -> {
                    put("error", result.code.name)
                    put("retryable", result.retryable)
                }
            }
        }.toString().also { require(it.encodeToByteArray().size <= MAX_BYTES) }

    fun decode(document: String): CliModelCatalog {
        require(document.encodeToByteArray().size <= MAX_BYTES)
        val root = Json.parseToJsonElement(document).jsonObject
        if (root.keys == setOf("error", "retryable")) {
            return CliModelCatalog.Failed(
                ModelErrorCode.valueOf(root.getValue("error").jsonPrimitive.content),
                root.getValue("retryable").jsonPrimitive.boolean,
            )
        }
        require(root.keys == setOf("models"))
        val models = root.getValue("models").jsonArray
        require(models.size in 1..MAX_MODELS)
        val values =
            models.map { value ->
                val row = value.jsonObject
                require(row.keys == setOf("id", "vision", "reasoning", "context"))
                val context = row.getValue("context")
                val numericContext = context.jsonPrimitive
                require(context == JsonNull || (!numericContext.isString && numericContext.longOrNull != null))
                val vision = row.getValue("vision").takeUnless { it == JsonNull }?.jsonPrimitive
                require(vision == null || !vision.isString)
                val reasoning = row.getValue("reasoning").takeUnless { it == JsonNull }?.jsonArray
                CliModelInfo(
                    row.getValue("id").jsonPrimitive.content,
                    vision?.boolean,
                    reasoning?.map { it.jsonPrimitive.content },
                    context.jsonPrimitive.longOrNull,
                )
            }
        require(values.map { it.id }.distinct().size == values.size)
        return CliModelCatalog.Listed(values)
    }
}
