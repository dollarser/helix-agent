package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationGesturePatterns
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.tools.framework.ToolJsonNumbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object AutomationGesturePatternInput {
    private val kinds = listOf("tap", "double_tap", "long_press", "swipe", "pinch", "rotate")

    fun schema(): JsonObject {
        val point =
            AutomationToolSchemas.obj(
                mapOf("x" to AutomationToolSchemas.number(), "y" to AutomationToolSchemas.number()),
                listOf("x", "y"),
            )
        val fields =
            mapOf(
                "kind" to
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("string"),
                            "enum" to
                                JsonArray(
                                    kinds.map(::JsonPrimitive),
                                ),
                        ),
                    ),
                "center" to point,
                "to" to point,
                "durationMillis" to AutomationToolSchemas.integer(1, 10_000),
                "fingers" to AutomationToolSchemas.integer(1, 10),
                "spacing" to AutomationToolSchemas.number(),
                "span" to AutomationToolSchemas.number(),
                "scale" to AutomationToolSchemas.number(),
                "degrees" to AutomationToolSchemas.number(),
            )
        return AutomationToolSchemas.obj(fields, listOf("kind", "center"))
    }

    fun strokes(pattern: JsonObject): List<AutomationStroke> {
        val kind = pattern.getValue("kind").jsonPrimitive.content
        val fields =
            when (kind) {
                "tap", "double_tap", "long_press" -> emptySet()
                "swipe" -> setOf("to", "fingers", "spacing")
                "pinch" -> setOf("span", "scale")
                "rotate" -> setOf("span", "degrees")
                else -> throw IllegalArgumentException("UNKNOWN_GESTURE_PATTERN")
            }
        require(pattern.keys.all { it in fields + setOf("kind", "center", "durationMillis") })
        require(pattern.keys.containsAll(setOf("kind", "center") + (fields - setOf("fingers", "spacing"))))
        val center = point(pattern.getValue("center").jsonObject)
        val duration = duration(pattern, kind)
        return when (kind) {
            "tap", "double_tap", "long_press" -> {
                require(kind != "long_press" || duration >= 500)
                AutomationGesturePatterns.tap(center, duration, kind == "double_tap")
            }

            "swipe" -> {
                AutomationGesturePatterns.swipe(
                    center,
                    point(pattern.getValue("to").jsonObject),
                    duration,
                    fingers(pattern),
                    if ("spacing" in pattern) number(pattern, "spacing") else 48f,
                )
            }

            "pinch" -> {
                AutomationGesturePatterns.pinch(center, number(pattern, "span"), number(pattern, "scale"), duration)
            }

            else -> {
                AutomationGesturePatterns.rotate(center, number(pattern, "span"), number(pattern, "degrees"), duration)
            }
        }
    }

    private fun duration(
        pattern: JsonObject,
        kind: String,
    ): Long {
        val fallback =
            when (kind) {
                "long_press" -> 600L
                "tap", "double_tap" -> 80L
                else -> 300L
            }
        return if ("durationMillis" in pattern) {
            requireNotNull(ToolJsonNumbers.longOrNull(pattern["durationMillis"]))
        } else {
            fallback
        }
    }

    private fun fingers(pattern: JsonObject): Int =
        if ("fingers" in pattern) {
            requireNotNull(ToolJsonNumbers.intOrNull(pattern["fingers"])).also { require(it in 1..10) }
        } else {
            1
        }

    private fun number(
        value: JsonObject,
        key: String,
    ): Float = requireNotNull(ToolJsonNumbers.decimalOrNull(value[key])).toFloat().also { require(it.isFinite()) }

    private fun point(value: JsonObject): AutomationPoint {
        require(value.keys == setOf("x", "y"))
        return AutomationPoint(number(value, "x"), number(value, "y"))
    }
}
