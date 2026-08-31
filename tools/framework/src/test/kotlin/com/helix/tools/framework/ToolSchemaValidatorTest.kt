package com.helix.tools.framework

import com.helix.tools.framework.TestFixtures.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaValidatorTest {
    private fun value(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun valid(
        schema: String,
        raw: String,
    ): Boolean = ToolSchemaValidator.validate(json(schema), value(raw)) is ToolSchemaValidation.Valid

    private fun invalid(
        schema: String,
        raw: String,
        fragment: String,
    ) {
        val result = ToolSchemaValidator.validate(json(schema), value(raw))
        val invalid = result as? ToolSchemaValidation.Invalid
        assertTrue(
            "expected Invalid containing '$fragment' for schema=$schema value=$raw, got $result",
            invalid != null && invalid.reasons.any { it.contains(fragment) },
        )
    }

    @Test
    fun objectValidationCoversRequiredPropertiesAndAdditional() {
        val schema =
            """
            {"type":"object","required":["path"],
             "properties":{"path":{"type":"string","minLength":1},"size":{"type":"integer","minimum":0}},
             "additionalProperties":false}
            """
        assertTrue(valid(schema, """{"path":"/tmp","size":3}"""))
        assertTrue(valid(schema, """{"path":"/tmp"}"""))
        invalid(schema, """{"size":3}""", "missing required property 'path'")
        invalid(schema, """{"path":"/tmp","extra":1}""", "additional property 'extra' is not allowed")
        invalid(schema, """{"path":""}""", "minimum is 1")
        invalid(schema, """{"path":"/tmp","size":-1}""", "less than minimum")
        invalid(schema, """{"path":"/tmp","size":"3"}""", "expected integer")
    }

    @Test
    fun additionalPropertiesSubschemaIsApplied() {
        val schema =
            """{"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":{"type":"integer"}}"""
        assertTrue(valid(schema, """{"a":"x","b":1}"""))
        invalid(schema, """{"a":"x","b":"nope"}""", "$[\"b\"]: value is string")
    }

    @Test
    fun nestedPathsAreReported() {
        val schema =
            """
            {"type":"object","properties":{
              "outer":{"type":"object","properties":{
                "list":{"type":"array","items":{"type":"string","minLength":1}}
              }}
            }}
            """
        invalid(schema, """{"outer":{"list":["ok",""]}}""", "$[\"outer\"][\"list\"][1]: string has 0 code points")
        invalid(schema, """{"outer":{"list":[1]}}""", "$[\"outer\"][\"list\"][0]: value is integer")
    }

    @Test
    fun arrayValidationCoversItemsAndBounds() {
        val schema = """{"type":"array","items":{"type":"integer","minimum":1},"minItems":1,"maxItems":2}"""
        assertTrue(valid(schema, """[1,2]"""))
        assertTrue(valid(schema, """[7]"""))
        invalid(schema, """[]""", "minimum is 1")
        invalid(schema, """[1,2,3]""", "maximum is 2")
        invalid(schema, """[1,"x"]""", "$[1]: value is string")
    }

    @Test
    fun stringLengthsCountCodePointsNotUtf16Units() {
        // one 4-byte emoji = 1 code point = 2 UTF-16 units
        assertTrue(valid("""{"type":"string","maxLength":1}""", "\"😀\""))
        invalid("""{"type":"string","maxLength":0}""", "\"😀\"", "maximum is 0")
        assertTrue(valid("""{"type":"string","minLength":2}""", "\"😀a\""))
        invalid("""{"type":"string","minLength":3}""", "\"😀a\"", "minimum is 3")
    }

    @Test
    fun patternsMatchUnanchored() {
        assertTrue(valid("""{"type":"string","pattern":"abc"}""", "\"xabcdef\""))
        assertTrue(valid("""{"type":"string","pattern":"^/"}""", "\"/tmp\""))
        invalid("""{"type":"string","pattern":"^/"}""", "\"tmp\"", "does not match pattern")
    }

    @Test
    fun numberAndIntegerSemantics() {
        assertTrue(valid("""{"type":"number"}""", "2.5"))
        assertTrue(valid("""{"type":"number"}""", "3"))
        assertTrue(valid("""{"type":"integer"}""", "3"))
        // an integral double is an integer
        assertTrue(valid("""{"type":"integer"}""", "3.0"))
        invalid("""{"type":"integer"}""", "2.5", "expected integer")
        invalid("""{"type":"integer"}""", "\"3\"", "expected integer")
        assertTrue(valid("""{"type":"boolean"}""", "true"))
        invalid("""{"type":"boolean"}""", "1", "expected boolean")
        invalid("""{"type":"boolean"}""", "\"true\"", "expected boolean")
        invalid("""{"type":"string"}""", "null", "expected string")
    }

    @Test
    fun numericBoundsAreInclusiveExclusiveAndBoundaryExact() {
        assertTrue(valid("""{"type":"number","minimum":1,"maximum":10}""", "1"))
        assertTrue(valid("""{"type":"number","minimum":1,"maximum":10}""", "10"))
        invalid("""{"type":"number","minimum":1}""", "0.999", "less than minimum")
        invalid("""{"type":"number","maximum":10}""", "10.001", "greater than maximum")
        assertTrue(valid("""{"type":"number","exclusiveMinimum":1,"exclusiveMaximum":10}""", "1.001"))
        invalid("""{"type":"number","exclusiveMinimum":1}""", "1", "not greater than exclusive minimum")
        invalid("""{"type":"number","exclusiveMaximum":10}""", "10", "not less than exclusive maximum")
    }

    @Test
    fun enumUsesStructuralEquality() {
        assertTrue(valid("""{"enum":["fast","safe"]}""", "\"fast\""))
        invalid("""{"enum":["fast","safe"]}""", "\"turbo\"", "not one of the allowed enum values")
        assertTrue(valid("""{"enum":[{"kind":"a"},{"kind":"b"}]}""", """{"kind":"b"}"""))
        invalid("""{"enum":[{"kind":"a"}]}""", """{"kind":"z"}""", "not one of the allowed enum values")
        // null is a legal enum member
        assertTrue(valid("""{"enum":[null]}""", "null"))
    }

    @Test
    fun typeArraysMatchAnyDeclaredType() {
        val schema = """{"type":["string","integer"]}"""
        assertTrue(valid(schema, "\"x\""))
        assertTrue(valid(schema, "3"))
        invalid(schema, "{}", "expected string | integer")
    }

    @Test
    fun withoutATypeOnlyNonTypeConstraintsApply() {
        // no type: any value passes by type, but explicit constraints still
        // apply when the value has the matching shape
        assertTrue(valid("""{"minLength":1}""", "3")) // number is not a string: constraint not applicable
        invalid("""{"minLength":1}""", "\"\"", "minimum is 1")
        assertTrue(valid("""{"minItems":1}""", "[1]")) // number is not an array: constraint not applicable
        invalid("""{"minItems":1}""", "[]", "minimum is 1")
    }

    @Test
    fun allViolationsAreCollectedNotJustTheFirst() {
        val schema =
            """
            {"type":"object","required":["a","b"],
             "properties":{"a":{"type":"string"},"b":{"type":"integer","minimum":5}},
             "additionalProperties":false}
            """
        // a: 1 is not a string; b: 1 < minimum 5; c: additional property
        val result = ToolSchemaValidator.validate(json(schema), value("""{"a":1,"b":1,"c":true}"""))
        val invalid = result as? ToolSchemaValidation.Invalid
        assertTrue("expected multiple reasons, got $result", invalid != null && invalid.reasons.size >= 3)
    }

    @Test
    fun malformedSchemaFailsClosed() {
        // a schema that should have been rejected at registration: validation
        // must not silently skip constraints
        val result = ToolSchemaValidator.validate(json("""{"type":42}"""), value("\"x\""))
        val invalid = result as? ToolSchemaValidation.Invalid
        assertTrue(
            "expected fail-closed invalid for malformed type, got $result",
            invalid != null && invalid.reasons.any { it.contains("malformed type declaration") },
        )
    }
}
