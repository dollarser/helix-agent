package com.helix.extensions.mobileuse.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Semantic tool schemas, shared by their descriptors and nested node output. */
internal object AutomationToolSchemas {
    fun nodeSchema(compact: Boolean = false) =
        obj(
            mapOf(
                "token" to str(32),
                "parentToken" to str(32),
                "clickTargetToken" to str(32),
                "longClick" to obj(mapOf("tool" to str(32), "token" to str(32)), listOf("tool", "token")),
                "ancestorContexts" to ancestorContextsSchema(),
                "depth" to integer(0),
                "className" to str(512),
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "clickable" to bool(),
                "offscreen" to bool(),
                "visibleToUser" to bool(),
                "longClickable" to bool(),
                "checkable" to bool(),
                "checked" to bool(),
                "editable" to bool(),
                "scrollable" to bool(),
                "scrollDirections" to directionsSchema(),
                "enabled" to bool(),
                "redacted" to bool(),
                "canImeEnter" to bool(),
                "bounds" to
                    obj(
                        mapOf("left" to number(), "top" to number(), "right" to number(), "bottom" to number()),
                        emptyList(),
                    ),
                "canSetProgress" to bool(),
                "range" to
                    obj(
                        mapOf("min" to number(), "max" to number(), "current" to number()),
                        listOf("min", "max", "current"),
                    ),
            ),
            if (compact) {
                listOf("token", "clickable", "enabled")
            } else {
                listOf(
                    "token",
                    "parentToken",
                    "clickTargetToken",
                    "depth",
                    "className",
                    "text",
                    "contentDescription",
                    "viewId",
                    "clickable",
                    "longClickable",
                    "checkable",
                    "checked",
                    "editable",
                    "scrollable",
                    "enabled",
                    "canImeEnter",
                )
            },
        )

    private fun ancestorContextsSchema() =
        array(
            obj(
                mapOf("className" to str(256), "viewId" to str(256), "labels" to array(str(96), 4)),
                listOf("className", "viewId", "labels"),
            ),
            2,
        )

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String>,
    ) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        if (properties.isNotEmpty()) {
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (k, v) ->
                        put(k, v)
                    }
                },
            )
        }
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    fun str(max: Int) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("maxLength", JsonPrimitive(max))
        }

    fun integer(
        min: Int,
        max: Int? = null,
    ) = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("minimum", JsonPrimitive(min))
        max?.let { put("maximum", JsonPrimitive(it)) }
    }

    fun bool() = buildJsonObject { put("type", JsonPrimitive("boolean")) }

    fun array(
        items: JsonObject,
        max: Int,
    ) = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("maxItems", JsonPrimitive(max))
    }

    fun number() = buildJsonObject { put("type", JsonPrimitive("number")) }

    private fun directionsSchema() =
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put(
                "items",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    val values = listOf("forward", "backward", "up", "down", "left", "right").map(::JsonPrimitive)
                    put("enum", JsonArray(values))
                },
            )
            put("maxItems", JsonPrimitive(6))
        }
}
