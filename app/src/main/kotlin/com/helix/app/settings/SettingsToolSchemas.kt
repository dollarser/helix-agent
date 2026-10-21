package com.helix.app.settings

import com.helix.core.model.Capability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Explicit result contracts; capability availability never doubles as operation authorization. */
internal object SettingsToolSchemas {
    private val text = buildJsonObject { put("type", JsonPrimitive("string")) }

    private fun array(items: JsonObject) =
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", items)
        }

    private fun objectSchema(
        fields: Map<String, JsonObject>,
        required: Set<String> = fields.keys,
    ) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    val inspectOutput =
        objectSchema(
            mapOf(
                "currentProviderId" to text,
                "currentModel" to text,
                "currentMode" to text,
                "currentReasoning" to text,
                "profile" to text,
                "advancedAvailable" to buildJsonObject { put("type", JsonPrimitive("boolean")) },
                "guidance" to text,
                "capabilities" to objectSchema(Capability.entries.associate { it.name to text }),
                "models" to
                    array(
                        objectSchema(
                            mapOf(
                                "providerId" to text,
                                "displayName" to text,
                                "model" to text,
                                "reasoning" to array(text),
                            ),
                        ),
                    ),
            ),
            setOf("profile", "advancedAvailable", "guidance", "capabilities", "models"),
        )
    val applyOutput =
        objectSchema(
            mapOf(
                "applied" to buildJsonObject { put("type", JsonPrimitive("boolean")) },
                "effectiveFor" to
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", JsonArray(listOf(JsonPrimitive("future_inputs"))))
                    },
            ),
        )
}
