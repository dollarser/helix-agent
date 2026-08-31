package com.helix.tools.framework

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JsonPrimitive kind classification for the schema subset (HXA-031).
 *
 * kotlinx 1.9.0 exposes only [JsonPrimitive.isString] and [JsonPrimitive.content]
 * publicly, so boolean/number are derived from the canonical literal content —
 * always guarded by [JsonPrimitive.isString] first, because a STRING literal
 * `"true"` or `"123"` is a string, never a boolean/number (the same strict
 * reading the provider:api capability codec applies).
 */
internal object ToolJsonPrimitives {
    /** true only for the JSON literals `true`/`false` (a string "true" is not a boolean). */
    fun isBoolean(p: JsonPrimitive): Boolean = !p.isString && (p.content == "true" || p.content == "false")

    /** true only for JSON number literals (a string "123" is not a number). */
    fun isNumber(p: JsonPrimitive): Boolean = !p.isString && (p.longOrNull != null || p.doubleOrNull != null)

    /** The boolean literal value, or null when the primitive is not a boolean literal. */
    fun booleanOf(p: JsonPrimitive): Boolean? =
        if (!p.isString) {
            when (p.content) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } else {
            null
        }

    /** The numeric value as a double, or null when the primitive is not a number literal. */
    fun doubleOf(p: JsonPrimitive): Double? =
        if (!p.isString) {
            p.longOrNull?.toDouble() ?: p.doubleOrNull
        } else {
            null
        }
}
