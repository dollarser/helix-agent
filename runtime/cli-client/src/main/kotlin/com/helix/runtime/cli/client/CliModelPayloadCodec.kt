package com.helix.runtime.cli.client

import com.helix.core.model.ArtifactRef
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.model.VisionLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class CliModelProvider(
    val wireId: String,
) {
    CODEX("codex"),
    CLAUDE("claude"),
    GROK("grok"),
    COPILOT("copilot"),
}

data class CliModelEnvelope(
    val provider: CliModelProvider,
    val request: ModelRequest,
    val images: List<CliImageSnapshot> = emptyList(),
)

object CliModelRequestCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_TEXT_BYTES = 512 * 1024

    fun encode(
        request: ModelRequest,
        provider: CliModelProvider = CliModelProvider.CODEX,
        images: List<CliImageSnapshot> = emptyList(),
    ): ByteArray {
        val references = request.messages.flatMap { it.images }.toSet()
        require(images.map { it.reference }.toSet() == references && images.size == references.size) {
            "image snapshots must exactly match message references"
        }
        require(images.isEmpty() || provider == CliModelProvider.CODEX)
        require(images.sumOf { it.base64.length.toLong() } <= VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES)
        val version =
            if (images.isNotEmpty()) {
                3
            } else if (provider == CliModelProvider.CODEX) {
                1
            } else {
                2
            }
        val bytes =
            buildJsonObject {
                put("version", version)
                if (version == 3) put("images", encodeImages(images))
                if (provider != CliModelProvider.CODEX) put("providerId", provider.wireId)
                put("model", request.model)
                put("messages", buildJsonArray { request.messages.forEach { add(encodeMessage(it, version == 3)) } })
                put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
                request.temperature?.let { put("temperature", it) }
                request.maxOutputTokens?.let { put("maxOutputTokens", it) }
                request.seed?.let { put("seed", it) }
                put("stopSequences", buildJsonArray { request.stopSequences.forEach { add(JsonPrimitive(it)) } })
                put("reasoning", request.reasoning.name)
            }.toString().encodeToByteArray()
        return bytes
    }

    fun decode(bytes: ByteArray): ModelRequest = decodeEnvelope(bytes).request

    fun decodeEnvelope(bytes: ByteArray): CliModelEnvelope {
        require(bytes.isNotEmpty())
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val version = root.getValue("version").jsonPrimitive.long
        require(version in 1L..3L)
        root.strictObject(
            when (version) {
                1L -> {
                    REQUEST_KEYS
                }

                2L -> {
                    REQUEST_KEYS + "providerId"
                }

                else -> {
                    REQUEST_KEYS +
                        "images"
                }
            },
        )
        val provider =
            if (version != 2L) {
                CliModelProvider.CODEX
            } else {
                val id = root.getValue("providerId").jsonPrimitive
                require(id.isString)
                CliModelProvider.entries.single { it.wireId == id.content }
            }
        val request =
            ModelRequest(
                model = root.getValue("model").jsonPrimitive.content,
                messages = root.getValue("messages").jsonArray.map { decodeMessage(it, version == 3L) },
                tools = root.getValue("tools").jsonArray.map(::decodeTool),
                temperature = root["temperature"]?.jsonPrimitive?.double,
                maxOutputTokens = root["maxOutputTokens"]?.jsonPrimitive?.long,
                seed = root["seed"]?.jsonPrimitive?.long,
                stopSequences = root.getValue("stopSequences").jsonArray.map { it.jsonPrimitive.content },
                reasoning = ReasoningEffort.valueOf(root.getValue("reasoning").jsonPrimitive.content),
            )
        val images = if (version == 3L) decodeImages(root.getValue("images").jsonArray) else emptyList()
        val references = request.messages.flatMap { it.images }.toSet()
        require(images.map { it.reference }.toSet() == references && images.size == references.size)
        require(images.sumOf { it.base64.length.toLong() } <= VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES)
        return CliModelEnvelope(provider, request, images)
    }

    private fun encodeMessage(
        message: ModelMessage,
        withImages: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("role", message.role.name)
            if (withImages) {
                put(
                    "images",
                    buildJsonArray {
                        message.images.forEach { image ->
                            add(
                                buildJsonObject {
                                    put("ref", image.ref.value)
                                    put("mediaType", image.mediaType)
                                },
                            )
                        }
                    },
                )
            }
            put("text", message.text)
            message.toolCallId?.let { put("toolCallId", it.value) }
            message.toolName?.let { put("toolName", it.value) }
            put(
                "toolCalls",
                buildJsonArray {
                    message.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id.value)
                                put("name", call.name.value)
                                put("argumentsJson", call.argumentsJson)
                            },
                        )
                    }
                },
            )
        }

    private fun decodeMessage(
        element: kotlinx.serialization.json.JsonElement,
        withImages: Boolean,
    ): ModelMessage {
        val obj = element.strictObject(if (withImages) MESSAGE_KEYS + "images" else MESSAGE_KEYS)
        return ModelMessage(
            role = ModelRole.valueOf(obj.getValue("role").jsonPrimitive.content),
            images =
                if (withImages) {
                    obj.getValue("images").jsonArray.map { value ->
                        val image = value.strictObject(setOf("ref", "mediaType"))
                        ImageReference(
                            ArtifactRef(image.getValue("ref").jsonPrimitive.content),
                            image.getValue("mediaType").jsonPrimitive.content,
                        )
                    }
                } else {
                    emptyList()
                },
            text = obj.getValue("text").jsonPrimitive.content,
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.content?.let(::ToolCallId),
            toolName = obj["toolName"]?.jsonPrimitive?.content?.let(::ToolName),
            toolCalls =
                obj.getValue("toolCalls").jsonArray.map { item ->
                    val call = item.strictObject(TOOL_CALL_KEYS)
                    AssistantToolCall(
                        ToolCallId(call.getValue("id").jsonPrimitive.content),
                        ToolName(call.getValue("name").jsonPrimitive.content),
                        call.getValue("argumentsJson").jsonPrimitive.content,
                    )
                },
        )
    }

    private fun encodeTool(tool: ModelToolSchema): JsonObject =
        buildJsonObject {
            put("name", tool.name.value)
            put("description", tool.description)
            put("inputSchemaJson", tool.inputSchemaJson)
        }

    private fun decodeTool(element: kotlinx.serialization.json.JsonElement): ModelToolSchema {
        val obj = element.strictObject(TOOL_KEYS)
        return ModelToolSchema(
            ToolName(obj.getValue("name").jsonPrimitive.content),
            obj.getValue("description").jsonPrimitive.content,
            obj.getValue("inputSchemaJson").jsonPrimitive.content,
        )
    }

    private fun encodeImages(images: List<CliImageSnapshot>) =
        buildJsonArray {
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("ref", image.reference.ref.value)
                        put("mediaType", image.reference.mediaType)
                        put("base64", image.base64)
                    },
                )
            }
        }

    private fun decodeImages(images: JsonArray): List<CliImageSnapshot> {
        require(images.size <= 4)
        return images.map { value ->
            val image = value.strictObject(setOf("ref", "mediaType", "base64"))
            CliImageSnapshot(
                ImageReference(
                    ArtifactRef(image.getValue("ref").jsonPrimitive.content),
                    image.getValue("mediaType").jsonPrimitive.content,
                ),
                image.getValue("base64").jsonPrimitive.content,
            )
        }
    }

    private val REQUEST_KEYS =
        setOf(
            "version",
            "model",
            "messages",
            "tools",
            "temperature",
            "maxOutputTokens",
            "seed",
            "stopSequences",
            "reasoning",
        )
    private val MESSAGE_KEYS = setOf("role", "text", "toolCallId", "toolName", "toolCalls")
    private val TOOL_CALL_KEYS = setOf("id", "name", "argumentsJson")
    private val TOOL_KEYS = setOf("name", "description", "inputSchemaJson")
}

object CliModelEventCodec {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_EVENTS = 2048

    fun encode(events: List<ModelEvent>): ByteArray {
        require(events.isNotEmpty())
        val bytes =
            buildJsonObject {
                put("version", 1)
                put("events", buildJsonArray { events.forEach { add(encodeEvent(it)) } })
            }.toString().encodeToByteArray()
        return bytes
    }

    fun decode(bytes: ByteArray): List<ModelEvent> {
        require(bytes.isNotEmpty())
        val root = Json.parseToJsonElement(bytes.decodeToString()).strictObject(setOf("version", "events"))
        require(root.getValue("version").jsonPrimitive.long == 1L)
        val rows = root.getValue("events").jsonArray
        require(rows.isNotEmpty())
        val events = rows.map(::decodeEvent)
        require(events.count { it.terminal } == 1 && events.last().terminal) { "model event terminal mismatch" }
        return events
    }

    internal fun encodeEvent(event: ModelEvent): JsonObject =
        buildJsonObject {
            when (event) {
                is ModelEvent.TextDelta -> {
                    put("type", "text")
                    put("text", event.text)
                }

                is ModelEvent.ReasoningDelta -> {
                    put("type", "reasoning")
                    put("text", event.text)
                }

                is ModelEvent.ToolCallStarted -> {
                    put("type", "toolStart")
                    put("index", event.index)
                    put("id", event.id.value)
                    put("name", event.name)
                }

                is ModelEvent.ToolArgumentsDelta -> {
                    put("type", "toolArgs")
                    put("index", event.index)
                    put("jsonFragment", event.jsonFragment)
                }

                is ModelEvent.ToolCallFinished -> {
                    put("type", "toolFinish")
                    put("index", event.index)
                }

                is ModelEvent.Usage -> {
                    put("type", "usage")
                    event.inputTokens?.let { put("inputTokens", it) }
                    event.outputTokens?.let { put("outputTokens", it) }
                }

                is ModelEvent.Refusal -> {
                    put("type", "refusal")
                    event.safeReason?.let { put("safeReason", it) }
                }

                is ModelEvent.Error -> {
                    put("type", "error")
                    put("code", event.code.name)
                    put("retryable", event.retryable)
                }

                is ModelEvent.Completed -> {
                    put("type", "completed")
                    event.finishReason?.let { put("finishReason", it) }
                }
            }
        }

    internal fun decodeEvent(element: kotlinx.serialization.json.JsonElement): ModelEvent {
        val obj = element.jsonObject
        val type = obj.getValue("type").jsonPrimitive.content
        require(obj.keys.all { it in EVENT_KEYS.getValue(type) })
        return when (type) {
            "text" -> {
                ModelEvent.TextDelta(obj.getValue("text").jsonPrimitive.content)
            }

            "reasoning" -> {
                ModelEvent.ReasoningDelta(obj.getValue("text").jsonPrimitive.content)
            }

            "toolStart" -> {
                ModelEvent.ToolCallStarted(
                    obj
                        .getValue("index")
                        .jsonPrimitive.long
                        .toInt(),
                    ToolCallId(obj.getValue("id").jsonPrimitive.content),
                    obj.getValue("name").jsonPrimitive.content,
                )
            }

            "toolArgs" -> {
                ModelEvent.ToolArgumentsDelta(
                    obj
                        .getValue("index")
                        .jsonPrimitive.long
                        .toInt(),
                    obj.getValue("jsonFragment").jsonPrimitive.content,
                )
            }

            "toolFinish" -> {
                ModelEvent.ToolCallFinished(
                    obj
                        .getValue("index")
                        .jsonPrimitive.long
                        .toInt(),
                )
            }

            "usage" -> {
                ModelEvent.Usage(obj["inputTokens"]?.jsonPrimitive?.long, obj["outputTokens"]?.jsonPrimitive?.long)
            }

            "refusal" -> {
                ModelEvent.Refusal(obj["safeReason"]?.jsonPrimitive?.content)
            }

            "error" -> {
                ModelEvent.Error(
                    ModelErrorCode.valueOf(obj.getValue("code").jsonPrimitive.content),
                    obj.getValue("retryable").jsonPrimitive.boolean,
                )
            }

            "completed" -> {
                ModelEvent.Completed(obj["finishReason"]?.jsonPrimitive?.content)
            }

            else -> {
                error("unknown model event")
            }
        }
    }

    private val ModelEvent.terminal: Boolean
        get() = this is ModelEvent.Completed || this is ModelEvent.Refusal || this is ModelEvent.Error

    private val EVENT_KEYS =
        mapOf(
            "text" to setOf("type", "text"),
            "reasoning" to setOf("type", "text"),
            "toolStart" to setOf("type", "index", "id", "name"),
            "toolArgs" to setOf("type", "index", "jsonFragment"),
            "toolFinish" to setOf("type", "index"),
            "usage" to setOf("type", "inputTokens", "outputTokens"),
            "refusal" to setOf("type", "safeReason"),
            "error" to setOf("type", "code", "retryable"),
            "completed" to setOf("type", "finishReason"),
        )
}

private fun kotlinx.serialization.json.JsonElement.strictObject(keys: Set<String>): JsonObject =
    jsonObject.also { require(it.keys.all(keys::contains)) }
