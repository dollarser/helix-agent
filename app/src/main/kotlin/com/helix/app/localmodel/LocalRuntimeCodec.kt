package com.helix.app.localmodel

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.provider.api.local.LocalRuntimeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/** Private runtime DTO, independent of any network provider's wire format. */
internal object LocalRuntimeCodec {
    fun encode(request: ModelRequest): ByteArray {
        require(request.messages.none { it.images.isNotEmpty() }) { "Local vision is not available" }
        require(request.stopSequences.isEmpty()) { "Local stop sequences are not supported" }
        require(request.temperature == null || request.temperature == 0.0) { "Local baseline uses greedy decoding" }
        return buildJsonObject {
            put("thinking", request.reasoning != ReasoningEffort.OFF)
            put("limit", (request.maxOutputTokens ?: 1024).coerceAtMost(8192))
            put(
                "history",
                buildJsonArray {
                    request.messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("role", message.role.name.lowercase(java.util.Locale.ROOT))
                                put("text", message.modelText)
                                put("toolName", message.toolName?.value.orEmpty())
                                put("callId", message.toolCallId?.value.orEmpty())
                                put(
                                    "calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { call ->
                                            add(
                                                buildJsonObject {
                                                    put("id", call.id.value)
                                                    put("name", call.name.value)
                                                    put("arguments", call.argumentsJson)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "functions",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name.value)
                                put("description", tool.description)
                                put("schema", tool.inputSchemaJson)
                            },
                        )
                    }
                },
            )
        }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): List<ModelEvent> {
        require(bytes.size <= MAX_BYTES)
        val payload = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        payload["error"]?.jsonPrimitive?.contentOrNull?.let {
            throw LocalRuntimeException(ModelErrorCode.valueOf(it))
        }
        val usage =
            ModelEvent.Usage(
                payload.getValue("inputTokens").jsonPrimitive.long,
                payload.getValue("outputTokens").jsonPrimitive.long,
            )
        if (payload.getValue("finish").jsonPrimitive.content == "length") {
            // An unfinished generation must never execute a partially parsed call. Retain
            // measured usage even when no output can be safely delivered to the Harness.
            return listOf(usage, ModelEvent.Error(ModelErrorCode.LOCAL_OUTPUT_LIMIT, false))
        }
        return buildList {
            payload
                .getValue("reasoning")
                .jsonPrimitive.content
                .chunked(8192)
                .filter { it.isNotEmpty() }
                .forEach { add(ModelEvent.ReasoningDelta(it)) }
            payload
                .getValue("text")
                .jsonPrimitive.content
                .chunked(8192)
                .filter { it.isNotEmpty() }
                .forEach { add(ModelEvent.TextDelta(it)) }
            payload.getValue("calls").jsonArray.forEachIndexed { index, element ->
                val call = element.jsonObject
                add(
                    ModelEvent.ToolCallStarted(
                        index,
                        ToolCallId(UUID.randomUUID().toString()),
                        call.getValue("name").jsonPrimitive.content,
                    ),
                )
                call.getValue("arguments").jsonPrimitive.content.chunked(8192).forEach {
                    add(ModelEvent.ToolArgumentsDelta(index, it))
                }
                add(ModelEvent.ToolCallFinished(index))
            }
            add(usage)
            add(
                ModelEvent.Completed(
                    if (payload
                            .getValue(
                                "calls",
                            ).jsonArray
                            .isEmpty()
                    ) {
                        payload.getValue("finish").jsonPrimitive.content
                    } else {
                        "tool_calls"
                    },
                ),
            )
        }
    }

    const val MAX_BYTES = 1024 * 1024
}
