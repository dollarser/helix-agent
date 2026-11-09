package com.helix.core.model

import com.helix.core.model.internal.JsonNode
import com.helix.core.model.internal.ModelJson
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelJsonNumbersTest {
    @Test
    fun rejectsExcessiveDepthBeforeRecursiveParsingWithoutChangingWireNumbers() {
        val accepted =
            "[".repeat(JsonInputLimits.MAX_DEPTH + 1) + "1e99999999999999999999" +
                "]".repeat(JsonInputLimits.MAX_DEPTH + 1)
        ModelJson.parse(accepted)
        val rejected = "[".repeat(JsonInputLimits.MAX_DEPTH + 2) + "0" + "]".repeat(JsonInputLimits.MAX_DEPTH + 2)
        assertThrows<IllegalArgumentException>("nesting budget") { ModelJson.parse(rejected) }
        val extreme = "[".repeat(10000) + "0" + "]".repeat(10000)
        assertThrows<IllegalArgumentException>("no stack overflow") { ModelJson.parse(extreme) }
    }

    @Test
    fun nestingPreflightIgnoresEscapedStringsAndRejectsExcessiveNodeCounts() {
        val literal = "[".repeat(10000) + "\\\"" + "]".repeat(10000)
        val raw =
            com.helix.core.model.internal.Json
                .string(literal)
        assertEquals(JsonNode.Str(literal), ModelJson.parse(raw))
        val nodes = "[" + List(JsonInputLimits.MAX_NODES) { "0" }.joinToString(",") + "]"
        assertThrows<IllegalArgumentException>("node budget") { ModelJson.parse(nodes) }
    }

    @Test
    fun preservesEveryLegalNumericFormWithoutRoundingOrRangeConversion() {
        val numbers =
            listOf(
                "0",
                "-0",
                "42",
                "-42",
                "0.7",
                "-0.0",
                "1.2300",
                "1e3",
                "1E+3",
                "1e-3",
                "-1.25E+12",
                "9223372036854775808",
                "-9223372036854775809",
                "0.123456789012345678901234567890",
                "1e400",
                "1e-400",
                "1e99999999999999999999",
            )
        for (number in numbers) {
            assertEquals(JsonNode.NumberToken(number), ModelJson.parse(number))
            val schema =
                """{"type":"object","properties":{"value":{"type":"number"""" +
                    ""","default":$number,"enum":[$number],"minimum":$number}}}"""
            assertEquals(schema, ModelToolSchema(ToolName("sample"), "sample", schema).inputSchemaJson)
            val arguments = """{"nested":[{"value":$number}]}"""
            assertEquals(arguments, AssistantToolCall(ToolCallId("call"), ToolName("sample"), arguments).argumentsJson)
        }
    }

    @Test
    fun malformedNumbersRemainRejectedInSchemasAndHistory() {
        for (number in listOf(
            "01",
            "-01",
            "+1",
            ".5",
            "-.5",
            "1.",
            "1e",
            "1e+",
            "1e-",
            "1.e2",
            "--1",
            "NaN",
            "Infinity",
            "-Infinity",
            "0x10",
            "1_000",
            "1 2",
        )) {
            val json = """{"value":$number}"""
            assertThrows<IllegalArgumentException>(number) { ModelToolSchema(ToolName("sample"), "sample", json) }
            assertThrows<IllegalArgumentException>(
                number,
            ) { AssistantToolCall(ToolCallId("call"), ToolName("sample"), json) }
        }
    }

    @Test
    fun externalNumbersDoNotRelaxOtherJsonBoundaries() {
        for (json in listOf("""{"x":0.7,"x":0.8}""", """{"x":[1e3,]}""", """{"x":0.7} trailing""", """{"x":0.7""")) {
            assertThrows<IllegalArgumentException>(json) { ModelToolSchema(ToolName("sample"), "sample", json) }
            assertThrows<IllegalArgumentException>(
                json,
            ) { AssistantToolCall(ToolCallId("call"), ToolName("sample"), json) }
        }
    }
}
