package com.helix.app.chat

import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolCallPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Reserved model-facing metadata that is stripped before any business tool validation. */
internal object ToolPresentationMetadata {
    const val RESERVED_INTENT_KEY = "__helix_intent"

    data class Extracted(
        val businessArgumentsJson: String,
        val presentation: ToolCallPresentation,
    )

    fun augment(schema: ModelToolSchema): ModelToolSchema {
        val root =
            Json.parseToJsonElement(schema.inputSchemaJson) as? JsonObject
                ?: throw IllegalArgumentException("tool schema must be an object")
        val properties =
            when (val value = root["properties"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> value
                else -> throw IllegalArgumentException("tool schema properties must be an object")
            }
        require(RESERVED_INTENT_KEY !in properties) {
            "tool schema occupies reserved presentation key $RESERVED_INTENT_KEY"
        }
        val required =
            root["required"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .orEmpty()
        require(RESERVED_INTENT_KEY !in required) {
            "tool schema requires reserved presentation key $RESERVED_INTENT_KEY"
        }
        val intentSchema =
            buildJsonObject {
                put("type", "string")
                put("maxLength", ToolCallPresentation.MAX_INTENT_LENGTH)
                put(
                    "description",
                    "Brief single-line purpose of this call for the user. Describe what you are trying to do, " +
                        "not a result or success claim.",
                )
            }
        val augmented =
            JsonObject(
                root +
                    (
                        "properties" to
                            JsonObject(properties + (RESERVED_INTENT_KEY to intentSchema))
                    ),
            )
        return schema.copy(inputSchemaJson = augmented.toString())
    }

    /**
     * Invalid presentation metadata degrades to the deterministic UI fallback. Malformed business
     * JSON remains malformed so the existing validation path can reject it honestly.
     */
    fun extract(rawArgumentsJson: String): Extracted {
        val normalized = rawArgumentsJson.ifBlank { "{}" }
        val parsed =
            runCatching { Json.parseToJsonElement(normalized) }.getOrNull() as? JsonObject
        return when {
            parsed == null -> {
                Extracted(rawArgumentsJson, ToolCallPresentation.EMPTY)
            }

            RESERVED_INTENT_KEY !in parsed -> {
                Extracted(normalized, ToolCallPresentation.EMPTY)
            }

            else -> {
                val candidate =
                    parsed[RESERVED_INTENT_KEY]
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }
                        ?.jsonPrimitive
                        ?.contentOrNull
                val business = JsonObject(parsed - RESERVED_INTENT_KEY).toString()
                Extracted(business, ToolCallPresentation(sanitize(candidate)))
            }
        }
    }

    fun sanitize(value: String?): String? =
        value
            ?.takeIf { intent -> intent.none(ToolCallPresentation::isForbiddenCharacter) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { it.length <= ToolCallPresentation.MAX_INTENT_LENGTH }
            ?.takeIf { ForbiddenContentGuard.reasonFor(it) == null }
            ?.takeIf { !RESULT_CLAIM_PREFIX.containsMatchIn(it) }

    val PROMPT_GUIDANCE =
        com.helix.app.chat.packagedPromptTemplates
            .text("tool-presentation")
            .trim()

    private val RESULT_CLAIM_PREFIX =
        Regex(
            "^(?:✅|✓|done\\b|completed\\b|succeeded\\b|success\\b|fixed\\b|passed\\b|" +
                "\u5df2(?:\u6210\u529f|\u7ecf|\u5168\u90e8)?\u5b8c\u6210|" +
                "\u5df2\u6210\u529f|\u5b8c\u6210\u4e86|\u6210\u529f|" +
                "\u5df2\u4fee\u590d|\u4fee\u590d\u4e86|\u5df2\u901a\u8fc7|\u901a\u8fc7\u4e86|" +
                "successfully\\b|\u5df2\u83b7\u6279\u51c6|\u65e0\u9700\u5ba1\u6279|" +
                "ignore\\s+(?:all\\s+)?(?:previous|prior)\\b|" +
                "\u5ffd\u7565(?:\u4e4b\u524d|\u6240\u6709)\u6307\u4ee4)",
            RegexOption.IGNORE_CASE,
        )
}
