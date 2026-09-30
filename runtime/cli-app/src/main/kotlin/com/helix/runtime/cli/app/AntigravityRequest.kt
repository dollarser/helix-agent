package com.helix.runtime.cli.app

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.runtime.cli.client.CliImageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/** Gemini-shaped payload, not OpenAI wire. Every internal tool name has a deterministic reversible mapping. */
internal class AntigravityRequest(
    private val input: ModelRequest,
    private val images: List<CliImageSnapshot>,
    private val replay: (ModelMessage) -> JsonArray?,
) {
    // Only currently offered tools may be returned; history names are encoded but do not authorize new calls.
    val names: Map<String, String> = input.tools.map { it.name.value }.associateBy(::wireName)
    private val responses = mutableMapOf<String, Pair<String, String?>>()
    private val legacyFields =
        setOf(
            "type",
            "description",
            "properties",
            "required",
            "items",
            "enum",
            "format",
            "nullable",
            "anyOf",
        )

    fun encode(project: String): JsonObject =
        buildJsonObject {
            require(project.isNotBlank())
            put("project", project)
            put("model", input.model)
            put("requestId", "agent/${UUID.randomUUID()}")
            put("userAgent", "antigravity")
            put("requestType", "agent")
            put("request", payload())
        }

    private fun payload(): JsonObject =
        buildJsonObject {
            put("sessionId", UUID.randomUUID().toString())
            put("contents", antigravityHistory(input.messages, replay, ::content))
            val systemMessages = input.messages.filter { it.role == ModelRole.SYSTEM }
            val system = systemMessages.joinToString("\n\n") { it.modelText }
            if (system.isNotEmpty()) {
                val text = buildJsonObject { put("text", system) }
                put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(text))) })
            }
            if (input.tools.isNotEmpty()) {
                val tools = buildJsonObject { put("functionDeclarations", declarations()) }
                put("tools", JsonArray(listOf(tools)))
                val mode = buildJsonObject { put("mode", "VALIDATED") }
                put("toolConfig", buildJsonObject { put("functionCallingConfig", mode) })
            }
            put("generationConfig", generation())
        }

    private fun declarations(): JsonArray =
        JsonArray(
            input.tools.map { tool ->
                buildJsonObject {
                    put("name", wireName(tool.name.value))
                    put("description", tool.description)
                    val legacy = input.model.startsWith("claude-") || input.model.startsWith("gpt-oss-")
                    val schema = Json.parseToJsonElement(tool.inputSchemaJson).jsonObject
                    val field = if (legacy) "parameters" else "parametersJsonSchema"
                    put(field, if (legacy) legacySchema(schema) else schema)
                }
            },
        )

    private fun legacySchema(schema: JsonObject): JsonObject =
        JsonObject(
            schema
                .filterKeys {
                    it in legacyFields
                }.mapValues { (key, value) ->
                    when {
                        key == "properties" -> {
                            val properties = value.jsonObject.mapValues { legacySchema(it.value.jsonObject) }
                            JsonObject(properties)
                        }

                        key == "items" -> {
                            legacySchema(value.jsonObject)
                        }

                        key == "anyOf" -> {
                            JsonArray((value as JsonArray).map { legacySchema(it.jsonObject) })
                        }

                        else -> {
                            value
                        }
                    }
                },
        )

    private fun generation(): JsonObject =
        buildJsonObject {
            input.maxOutputTokens?.let { put("maxOutputTokens", it) }
            input.temperature?.let { put("temperature", it) }
            input.seed?.let { put("seed", it) }
            if (input.stopSequences.isNotEmpty()) {
                put(
                    "stopSequences",
                    JsonArray(input.stopSequences.map(::JsonPrimitive)),
                )
            }
            if (input.reasoning != ReasoningEffort.OFF) {
                put(
                    "thinkingConfig",
                    antigravityThinking(input.model, input.reasoning, input.maxOutputTokens),
                )
            }
        }

    private fun content(
        message: ModelMessage,
        original: JsonArray?,
    ): JsonObject =
        buildJsonObject {
            put("role", if (message.role == ModelRole.ASSISTANT) "model" else "user")
            val functions = original.orEmpty().mapNotNull { it.jsonObject["functionCall"] as? JsonObject }
            message.toolCalls.forEachIndexed { index, call ->
                val serverId =
                    functions
                        .getOrNull(index)
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.content
                responses[call.id.value] = call.name.value to serverId
            }
            put("parts", original ?: parts(message))
        }

    private fun parts(message: ModelMessage): JsonArray =
        JsonArray(
            buildList {
                if (message.role == ModelRole.TOOL) {
                    add(
                        buildJsonObject {
                            put(
                                "functionResponse",
                                buildJsonObject {
                                    val expected = requireNotNull(responses[requireNotNull(message.toolCallId).value])
                                    require(expected.first == requireNotNull(message.toolName).value)
                                    put("name", wireName(expected.first))
                                    expected.second?.let { put("id", it) }
                                    val value = runCatching { Json.parseToJsonElement(message.modelText) }.getOrNull()
                                    val result =
                                        value as? JsonObject ?: buildJsonObject {
                                            put("output", value ?: JsonPrimitive(message.modelText))
                                        }
                                    put("response", result)
                                },
                            )
                        },
                    )
                } else if (message.modelText.isNotEmpty()) {
                    add(buildJsonObject { put("text", message.modelText) })
                }
                message.images.forEach { reference ->
                    val image = images.single { it.reference == reference }
                    add(
                        buildJsonObject {
                            put(
                                "inlineData",
                                buildJsonObject {
                                    put("mimeType", reference.mediaType)
                                    put("data", image.base64)
                                },
                            )
                        },
                    )
                }
                message.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put(
                                "functionCall",
                                buildJsonObject {
                                    put("name", wireName(call.name.value))
                                    put("args", Json.parseToJsonElement(call.argumentsJson))
                                },
                            )
                        },
                    )
                }
            },
        )

    companion object {
        fun wireName(name: String): String =
            "hx_" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(name.toByteArray(Charsets.UTF_8))
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
    }
}
