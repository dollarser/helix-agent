package com.helix.tools.framework

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolJsonNumbersTest {
    private fun number(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun exactIntegralConversionsDoNotTruncateOrDefault() {
        listOf("1", "1.0", "1e0", "10e-1").forEach { assertEquals(1L, ToolJsonNumbers.longOrNull(number(it))) }
        assertEquals(Long.MAX_VALUE, ToolJsonNumbers.longOrNull(number("9223372036854775807.0")))
        assertEquals(Long.MIN_VALUE, ToolJsonNumbers.longOrNull(number("-9223372036854775808e0")))
        listOf("9223372036854775808", "-9223372036854775809", "1.00000000000000001", "1e-1000").forEach {
            assertNull(it, ToolJsonNumbers.longOrNull(number(it)))
        }
        assertNull(ToolJsonNumbers.longOrNull(JsonPrimitive("1")))
        assertNull(ToolJsonNumbers.longOrNull(null))
        assertEquals(Int.MAX_VALUE, ToolJsonNumbers.intOrNull(number("2147483647.0")))
        assertNull(ToolJsonNumbers.intOrNull(number("2147483648")))
    }

    @Test
    fun integerAndBoundsRemainExactBeyondDoublePrecision() {
        val integer = JsonObject(mapOf("type" to JsonPrimitive("integer")))
        assertTrue(ToolSchemaValidator.validate(integer, number("9007199254740993")) is ToolSchemaValidation.Valid)
        assertTrue(ToolSchemaValidator.validate(integer, number("9007199254740992.1")) is ToolSchemaValidation.Invalid)
        assertFalse(ToolJsonNumbers.isIntegral(number("1e-1000")))
        val maximum = JsonObject(mapOf("maximum" to number("9007199254740992")))
        assertTrue(ToolSchemaValidator.validate(maximum, number("9007199254740993")) is ToolSchemaValidation.Invalid)
        val exclusive = JsonObject(mapOf("exclusiveMinimum" to number("0.10000000000000000001")))
        val fractional = ToolSchemaValidator.validate(exclusive, number("0.10000000000000000002"))
        assertTrue(fractional is ToolSchemaValidation.Valid)
    }

    @Test
    fun enumUsesRecursiveNumericEqualityButNotNumericStrings() {
        val schema = Json.parseToJsonElement("""{"enum":[{"a":[1,0.1]}]}""") as JsonObject
        assertTrue(ToolSchemaValidator.validate(schema, number("""{"a":[1e0,0.10]}""")) is ToolSchemaValidation.Valid)
        assertTrue(ToolSchemaValidator.validate(schema, number("""{"a":["1",0.1]}""")) is ToolSchemaValidation.Invalid)
        val exact = Json.parseToJsonElement("""{"enum":[9007199254740992]}""") as JsonObject
        assertTrue(ToolSchemaValidator.validate(exact, number("9007199254740993")) is ToolSchemaValidation.Invalid)
    }

    @Test
    fun numericResourceLimitsAreExplicitAndBounded() {
        assertNotNull(ToolJsonNumbers.decimalOrNull(number("1e10000")))
        assertNotNull(ToolJsonNumbers.decimalOrNull(number("1e-10000")))
        listOf("1e10001", "1e-10001", "1e2147483648", "0e9999999999999999999999", "1".repeat(4097)).forEach {
            assertNull(it.take(40), ToolJsonNumbers.decimalOrNull(number(it)))
            assertTrue(ToolSchemaValidator.validate(JsonObject(emptyMap()), number(it)) is ToolSchemaValidation.Invalid)
        }
        assertNotNull(ToolJsonNumbers.decimalOrNull(number("1".repeat(4096))))
        assertNull(ToolJsonNumbers.decimalOrNull(JsonPrimitive(Double.NaN)))
        assertNull(ToolJsonNumbers.decimalOrNull(JsonPrimitive(Double.POSITIVE_INFINITY)))
    }

    @Test
    fun unconstrainedTreesStillHaveDepthAndNodeBudgets() {
        var value: JsonElement = JsonPrimitive(1)
        repeat(ToolJsonBudget.MAX_DEPTH) { value = JsonArray(listOf(value)) }
        assertTrue(ToolSchemaValidator.validate(JsonObject(emptyMap()), value) is ToolSchemaValidation.Valid)
        value = JsonArray(listOf(value))
        assertTrue(ToolSchemaValidator.validate(JsonObject(emptyMap()), value) is ToolSchemaValidation.Invalid)
        val tooWide = JsonArray(List(ToolJsonBudget.MAX_NODES) { JsonPrimitive(0) })
        assertTrue(ToolSchemaValidator.validate(JsonObject(emptyMap()), tooWide) is ToolSchemaValidation.Invalid)
        val schema = JsonObject(mapOf("default" to value))
        assertTrue(ToolSchema.check(schema).isNotEmpty())
    }

    @Test
    fun schemaBoundsAndUnsupportedKeywordsFailClosed() {
        val cases =
            listOf(
                """{"type":[]}""",
                """{"minItems":2,"maxItems":1}""",
                """{"minimum":9007199254740993,"maximum":9007199254740992}""",
                """{"anyOf":[{"type":"integer"}]}""",
                """{"enum":[1e10001]}""",
                """{"minimum":1,"exclusiveMaximum":1}""",
                """{"exclusiveMinimum":2,"maximum":1}""",
            )
        cases.forEach {
            val schema = Json.parseToJsonElement(it) as JsonObject
            assertTrue(it, ToolSchema.check(schema).isNotEmpty())
            assertTrue(ToolSchemaValidator.validate(schema, number("1")) is ToolSchemaValidation.Invalid)
        }
        val lengths = Json.parseToJsonElement("""{"minLength":1.0,"maxLength":1e1}""") as JsonObject
        assertTrue(ToolSchema.check(lengths).isEmpty())
        assertTrue(ToolSchemaValidator.validate(lengths, JsonPrimitive("")) is ToolSchemaValidation.Invalid)
    }

    @Test
    fun excessiveDiagnosticsAndEnumWorkFailWithExplicitResourceError() {
        val errors =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to
                        JsonObject(mapOf("type" to JsonPrimitive("string"))),
                ),
            )
        val result =
            ToolSchemaValidator.validate(errors, JsonArray(List(200) { JsonPrimitive(1) }))
                as ToolSchemaValidation.Invalid
        assertEquals(101, result.reasons.size)
        assertTrue(result.reasons.last().contains("resource limit"))
        val items = JsonObject(mapOf("enum" to JsonArray(List(2000) { JsonPrimitive(it) })))
        val schema = JsonObject(mapOf("items" to items))
        val costly =
            ToolSchemaValidator.validate(schema, JsonArray(List(1000) { JsonPrimitive(1999) }))
                as ToolSchemaValidation.Invalid
        assertTrue(costly.reasons.single().contains("resource limit"))
    }
}
