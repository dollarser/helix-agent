package com.helix.runtime.cli.client

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
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

object CliModelRequestCodec {
    const val MAX_BYTES = 512 * 1024

    fun encode(request: ModelRequest): ByteArray {
        require(request.messages.none { it.images.isNotEmpty() }) { "subscription IPC does not accept image references" }
        val bytes = buildJsonObject {
            put("version", 1)
            put("model", request.model)
            put("messages", buildJsonArray { request.messages.forEach { add(encodeMessage(it)) } })
            put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.seed?.let { put("seed", it) }
            put("stopSequences", buildJsonArray { request.stopSequences.forEach { add(JsonPrimitive(it)) } })
            put("reasoning", request.reasoning.name)
        }.toString().encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "model request exceeds IPC limit" }
        return bytes
    }

    fun decode(bytes: ByteArray): ModelRequest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString()).strictObject(REQUEST_KEYS)
        require(root.getValue("version").jsonPrimitive.long == 1L)
        return ModelRequest(
            model = root.getValue("model").jsonPrimitive.content,
            messages = root.getValue("messages").jsonArray.map(::decodeMessage),
            tools = root.getValue("tools").jsonArray.map(::decodeTool),
            temperature = root["temperature"]?.jsonPrimitive?.double,
            maxOutputTokens = root["maxOutputTokens"]?.jsonPrimitive?.long,
            seed = root["seed"]?.jsonPrimitive?.long,
            stopSequences = root.getValue("stopSequences").jsonArray.map { it.jsonPrimitive.content },
            reasoning = ReasoningEffort.valueOf(root.getValue("reasoning").jsonPrimitive.content),
        )
    }

    private fun encodeMessage(message: ModelMessage): JsonObject = buildJsonObject {
        put("role", message.role.name)
        put("text", message.text)
        message.toolCallId?.let { put("toolCallId", it.value) }
        message.toolName?.let { put("toolName", it.value) }
        put("toolCalls", buildJsonArray {
            message.toolCalls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id.value)
                    put("name", call.name.value)
                    put("argumentsJson", call.argumentsJson)
                })
            }
        })
    }

    private fun decodeMessage(element: kotlinx.serialization.json.JsonElement): ModelMessage {
        val obj = element.strictObject(MESSAGE_KEYS)
        return ModelMessage(
            role = ModelRole.valueOf(obj.getValue("role").jsonPrimitive.content),
            text = obj.getValue("text").jsonPrimitive.content,
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.content?.let(::ToolCallId),
            toolName = obj["toolName"]?.jsonPrimitive?.content?.let(::ToolName),
            toolCalls = obj.getValue("toolCalls").jsonArray.map { item ->
                val call = item.strictObject(TOOL_CALL_KEYS)
                AssistantToolCall(
                    ToolCallId(call.getValue("id").jsonPrimitive.content),
                    ToolName(call.getValue("name").jsonPrimitive.content),
                    call.getValue("argumentsJson").jsonPrimitive.content,
                )
            },
        )
    }

    private fun encodeTool(tool: ModelToolSchema): JsonObject = buildJsonObject {
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

    private val REQUEST_KEYS = setOf(
        "version", "model", "messages", "tools", "temperature", "maxOutputTokens", "seed", "stopSequences", "reasoning",
    )
    private val MESSAGE_KEYS = setOf("role", "text", "toolCallId", "toolName", "toolCalls")
    private val TOOL_CALL_KEYS = setOf("id", "name", "argumentsJson")
    private val TOOL_KEYS = setOf("name", "description", "inputSchemaJson")
}

object CliModelEventCodec {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_EVENTS = 2048

    fun encode(events: List<ModelEvent>): ByteArray {
        require(events.isNotEmpty() && events.size <= MAX_EVENTS)
        val bytes = buildJsonObject {
            put("version", 1)
            put("events", buildJsonArray { events.forEach { add(encodeEvent(it)) } })
        }.toString().encodeToByteArray()
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): List<ModelEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString()).strictObject(setOf("version", "events"))
        require(root.getValue("version").jsonPrimitive.long == 1L)
        val rows = root.getValue("events").jsonArray
        require(rows.isNotEmpty() && rows.size <= MAX_EVENTS)
        val events = rows.map(::decodeEvent)
        require(events.count { it.terminal } == 1 && events.last().terminal) { "model event terminal mismatch" }
        return events
    }

    private fun encodeEvent(event: ModelEvent): JsonObject = buildJsonObject {
        when (event) {
            is ModelEvent.TextDelta -> { put("type", "text"); put("text", event.text) }
            is ModelEvent.ReasoningDelta -> { put("type", "reasoning"); put("text", event.text) }
            is ModelEvent.ToolCallStarted -> {
                put("type", "toolStart"); put("index", event.index); put("id", event.id.value); put("name", event.name)
            }
            is ModelEvent.ToolArgumentsDelta -> {
                put("type", "toolArgs"); put("index", event.index); put("jsonFragment", event.jsonFragment)
            }
            is ModelEvent.ToolCallFinished -> { put("type", "toolFinish"); put("index", event.index) }
            is ModelEvent.Usage -> {
                put("type", "usage"); event.inputTokens?.let { put("inputTokens", it) }; event.outputTokens?.let { put("outputTokens", it) }
            }
            is ModelEvent.Refusal -> { put("type", "refusal"); event.safeReason?.let { put("safeReason", it) } }
            is ModelEvent.Error -> { put("type", "error"); put("code", event.code.name); put("retryable", event.retryable) }
            is ModelEvent.Completed -> { put("type", "completed"); event.finishReason?.let { put("finishReason", it) } }
        }
    }

    private fun decodeEvent(element: kotlinx.serialization.json.JsonElement): ModelEvent {
        val obj = element.jsonObject
        val type = obj.getValue("type").jsonPrimitive.content
        require(obj.keys.all { it in EVENT_KEYS.getValue(type) })
        return when (type) {
            "text" -> ModelEvent.TextDelta(obj.getValue("text").jsonPrimitive.content)
            "reasoning" -> ModelEvent.ReasoningDelta(obj.getValue("text").jsonPrimitive.content)
            "toolStart" -> ModelEvent.ToolCallStarted(
                obj.getValue("index").jsonPrimitive.long.toInt(),
                ToolCallId(obj.getValue("id").jsonPrimitive.content),
                obj.getValue("name").jsonPrimitive.content,
            )
            "toolArgs" -> ModelEvent.ToolArgumentsDelta(
                obj.getValue("index").jsonPrimitive.long.toInt(),
                obj.getValue("jsonFragment").jsonPrimitive.content,
            )
            "toolFinish" -> ModelEvent.ToolCallFinished(obj.getValue("index").jsonPrimitive.long.toInt())
            "usage" -> ModelEvent.Usage(obj["inputTokens"]?.jsonPrimitive?.long, obj["outputTokens"]?.jsonPrimitive?.long)
            "refusal" -> ModelEvent.Refusal(obj["safeReason"]?.jsonPrimitive?.content)
            "error" -> ModelEvent.Error(
                ModelErrorCode.valueOf(obj.getValue("code").jsonPrimitive.content),
                obj.getValue("retryable").jsonPrimitive.boolean,
            )
            "completed" -> ModelEvent.Completed(obj["finishReason"]?.jsonPrimitive?.content)
            else -> error("unknown model event")
        }
    }

    private val ModelEvent.terminal: Boolean
        get() = this is ModelEvent.Completed || this is ModelEvent.Refusal || this is ModelEvent.Error

    private val EVENT_KEYS = mapOf(
        "text" to setOf("type", "text"), "reasoning" to setOf("type", "text"),
        "toolStart" to setOf("type", "index", "id", "name"),
        "toolArgs" to setOf("type", "index", "jsonFragment"), "toolFinish" to setOf("type", "index"),
        "usage" to setOf("type", "inputTokens", "outputTokens"), "refusal" to setOf("type", "safeReason"),
        "error" to setOf("type", "code", "retryable"), "completed" to setOf("type", "finishReason"),
    )
}

private fun kotlinx.serialization.json.JsonElement.strictObject(keys: Set<String>): JsonObject =
    jsonObject.also { require(it.keys.all(keys::contains)) }
