package com.helix.tools.framework

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Exact JSON-number semantics shared by schema validation and execution argument readers. */
object ToolJsonNumbers {
    const val MAX_TOKEN_LENGTH = 4096
    const val MAX_ABSOLUTE_SCALE = 10000

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** Strings, non-JSON literals and numbers exceeding the documented resource budget are invalid. */
    @Suppress("ReturnCount") // Fail early before allocating/parsing untrusted numeric tokens.
    fun decimalOrNull(value: JsonElement?): BigDecimal? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.content.length > MAX_TOKEN_LENGTH) return null
        val token = primitive.content
        if (!NUMBER.matches(token)) return null
        val number = token.toBigDecimalOrNull() ?: return null
        return number.takeIf { it.scale() in -MAX_ABSOLUTE_SCALE..MAX_ABSOLUTE_SCALE }
    }

    fun isIntegral(value: JsonElement?): Boolean =
        decimalOrNull(value)?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true

    /** Integral decimal/exponent notation is accepted; fractional values and overflow never truncate. */
    fun longOrNull(value: JsonElement?): Long? =
        decimalOrNull(value)?.let { number ->
            try {
                number.longValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

    fun intOrNull(value: JsonElement?): Int? = longOrNull(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
