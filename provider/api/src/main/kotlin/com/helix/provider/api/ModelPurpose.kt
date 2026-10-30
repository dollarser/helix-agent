package com.helix.provider.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/** Directory-declared tasks, not inference health or tool-call capabilities. Empty means unknown. */
enum class ModelPurpose { CHAT, EMBEDDING, RERANK, IMAGE, AUDIO, VIDEO, OTHER }

/** Explicit server fields only. Opaque model IDs and aliases never imply a task. */
object ModelPurposes {
    fun parse(entry: JsonObject): Set<ModelPurpose> {
        val declarations =
            listOf(
                "capabilities",
                "supportedGenerationMethods",
                "endpoints",
                "tasks",
                "task",
                "type",
                "sub_type",
            ).mapNotNull(entry::get)
        val purposes = declarations.flatMap(::tokens).mapNotNull(::purpose).toSet()
        val capabilities = entry["capabilities"] as? JsonObject
        val deniedChat =
            capabilities?.any { (key, value) ->
                purpose(key) == ModelPurpose.CHAT && (
                    (value as? JsonPrimitive)?.booleanOrNull == false ||
                        ((value as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull == false
                )
            } == true
        return if (deniedChat) purposes - ModelPurpose.CHAT + ModelPurpose.OTHER else purposes
    }

    private fun tokens(value: JsonElement): List<String> =
        when (value) {
            is JsonPrimitive -> {
                listOfNotNull(value.contentOrNull)
            }

            is JsonArray -> {
                value.flatMap(::tokens)
            }

            is JsonObject -> {
                value
                    .filterValues {
                        (it is JsonPrimitive && it.booleanOrNull == true) || (
                            it is JsonObject &&
                                (it["supported"] as? JsonPrimitive)?.booleanOrNull == true
                        )
                    }.keys
                    .toList()
            }

            else -> {
                emptyList()
            }
        }

    private fun purpose(value: String): ModelPurpose? =
        when (taskToken(value)) {
            "chat", "chat.completions", "chat/completions", "responses", "messages",
            "generatecontent", "streamgeneratecontent", "conversational",
            -> ModelPurpose.CHAT

            "embedding", "embeddings", "embed", "embedcontent", "batchembedcontents" -> ModelPurpose.EMBEDDING

            "rerank", "reranker", "reranking" -> ModelPurpose.RERANK

            "image", "text-to-image", "image-to-image", "images/generations" -> ModelPurpose.IMAGE

            "audio", "speech-to-text", "text-to-speech", "transcription",
            "audio/speech", "audio/transcriptions",
            -> ModelPurpose.AUDIO

            "video", "text-to-video" -> ModelPurpose.VIDEO

            "completion", "completions", "fill-mask", "classification",
            "object-detection",
            -> ModelPurpose.OTHER

            else -> null
        }

    private fun taskToken(value: String): String {
        val path =
            if (value.startsWith("https://") || value.startsWith("http://")) {
                runCatching { URI(value).path }.getOrNull() ?: return ""
            } else {
                value.substringBefore('?').substringBefore('#')
            }
        return path
            .lowercase()
            .trimEnd('/')
            .removePrefix("/")
            .removePrefix("v1/")
    }
}
