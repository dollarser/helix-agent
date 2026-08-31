package com.helix.tools.framework

import com.helix.tools.framework.TestFixtures.json
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaCheckTest {
    private val dollar = "${'$'}"

    private fun violations(schema: String): List<String> = ToolSchema.check(json(schema))

    private fun assertInvalid(
        schema: String,
        fragment: String,
    ) {
        val v = violations(schema)
        assertTrue("expected violation containing '$fragment', got $v", v.any { it.contains(fragment) })
    }

    @Test
    fun minimalSchemasAreValid() {
        assertTrue(violations("""{}""").isEmpty())
        assertTrue(violations("""{"type":"object"}""").isEmpty())
        assertTrue(violations("""{"type":"string","description":"a path"}""").isEmpty())
    }

    @Test
    fun fullyFeaturedSubsetSchemaIsValid() {
        val schema =
            """
            {"type":"object",
             "properties":{
               "path":{"type":"string","minLength":1,"maxLength":512,"pattern":"^/"},
               "count":{"type":"integer","minimum":0,"maximum":100},
               "ratio":{"type":"number","exclusiveMinimum":0,"exclusiveMaximum":1},
               "mode":{"type":"string","enum":["fast","safe"]},
               "tags":{"type":"array","items":{"type":"string"},"minItems":0,"maxItems":8},
               "flag":{"type":"boolean"},
               "nested":{"type":"object","required":["id"],
                         "properties":{"id":{"type":"string"}},"additionalProperties":false}
             },
             "required":["path"],
             "additionalProperties":false,
             "description":"test tool input"}
            """
        assertTrue(violations(schema).isEmpty())
    }

    @Test
    fun annotationKeywordsAreAllowedAndIgnored() {
        val schema =
            """{"type":"boolean","title":"t","description":"d","default":true,"examples":[true],""" +
                """"${dollar}comment":"c"}"""
        assertTrue(violations(schema).isEmpty())
    }

    @Test
    fun unknownKeywordsAreRejected() {
        val unknowns =
            listOf(
                "format" to """{"type":"string","format":"uri"}""",
                "const" to """{"type":"string","const":"a"}""",
                "anyOf" to """{"anyOf":[{"type":"string"}]}""",
                "oneOf" to """{"oneOf":[{"type":"string"}]}""",
                "allOf" to """{"allOf":[{"type":"string"}]}""",
                "not" to """{"not":{"type":"string"}}""",
                "${dollar}ref" to """{"${dollar}ref":"#/definitions/x"}""",
                "definitions" to """{"definitions":{"x":{"type":"string"}}}""",
                "uniqueItems" to """{"type":"array","items":{"type":"string"},"uniqueItems":true}""",
                "multipleOf" to """{"type":"integer","multipleOf":2}""",
                "propertyNames" to """{"type":"object","propertyNames":{"type":"string"}}""",
                "patternProperties" to """{"type":"object","patternProperties":{"^a":{"type":"string"}}}""",
                "contains" to """{"type":"array","contains":{"type":"string"}}""",
                "if" to """{"if":{"type":"string"},"then":{"type":"string"},"else":{"type":"string"}}""",
                "${dollar}id" to """{"${dollar}id":"x","type":"string"}""",
                "${dollar}schema" to """{"${dollar}schema":"https://json-schema.org/draft","type":"string"}""",
            )
        unknowns.forEach { (keyword, schema) ->
            assertInvalid(schema, "unknown keyword '$keyword'")
        }
    }

    @Test
    fun outOfTypeKeywordsAreRejected() {
        assertInvalid("""{"type":"object","minLength":1}""", "not valid for the declared type")
        assertInvalid("""{"type":"string","items":{"type":"string"}}""", "not valid for the declared type")
        assertInvalid("""{"type":"boolean","minimum":0}""", "not valid for the declared type")
        assertInvalid("""{"type":"array","properties":{"x":{"type":"string"}}}""", "not valid for the declared type")
    }

    @Test
    fun withoutATypeEveryTypeSpecificKeywordIsPotentiallyApplicable() {
        val schema =
            """
            {"properties":{"x":{"type":"string"}},"minLength":1,"minimum":0,"items":{"type":"string"},"maxItems":3}
            """
        assertTrue(violations(schema).isEmpty())
    }

    @Test
    fun unknownTypeNamesAreRejected() {
        assertInvalid("""{"type":"format"}""", "unknown type 'format'")
        assertInvalid("""{"type":"null"}""", "unknown type 'null'")
        assertInvalid("""{"type":["string","any"]}""", "unknown type 'any'")
        assertInvalid("""{"type":"string","type2":1}""", "unknown keyword 'type2'")
    }

    @Test
    fun malformedTypeDeclarationsAreRejected() {
        assertInvalid("""{"type":42}""", "must be a string or an array of strings")
        assertInvalid("""{"type":true}""", "must be a string or an array of strings")
        assertInvalid("""{"type":["string",1]}""", "array entries must be type-name strings")
    }

    @Test
    fun enumMustBeANonEmptyArrayOfValues() {
        assertInvalid("""{"enum":[]}""", "must not be empty")
        assertInvalid("""{"enum":"x"}""", "must be a non-empty JSON array")
        // enum entries are VALUES: they are never recursively schema-checked
        assertTrue(violations("""{"type":"object","enum":[{"anyOf":[1],"type":"string"}]}""").isEmpty())
        // a null literal is a legal enum value
        assertTrue(violations("""{"enum":[null,1,"a"]}""").isEmpty())
    }

    @Test
    fun objectKeywordsAreStructurallyChecked() {
        assertInvalid("""{"type":"object","properties":"x"}""", "must be a JSON object of property name")
        assertInvalid("""{"type":"object","properties":{"x":true}}""", "subschema must be a JSON object")
        assertInvalid(
            """{"type":"object","properties":{"x":{"type":"string","format":"uri"}}}""",
            "unknown keyword 'format'",
        )
        assertInvalid("""{"type":"object","properties":{"":{"type":"string"}}}""", "property names must not be empty")
        assertInvalid("""{"type":"object","required":"x"}""", "must be a JSON array of property-name strings")
        assertInvalid("""{"type":"object","required":["x",1]}""", "entries must be strings")
        assertInvalid("""{"type":"object","additionalProperties":"no"}""", "must be true, false, or a schema object")
        assertTrue(violations("""{"type":"object","additionalProperties":{"type":"string"}}""").isEmpty())
    }

    @Test
    fun arrayKeywordsAreStructurallyChecked() {
        assertInvalid("""{"type":"array","items":true}""", "subschema must be a JSON object")
        // the tuple form (array of schemas) is not in the subset
        assertInvalid(
            """{"type":"array","items":[{"type":"string"},{"type":"integer"}]}""",
            "subschema must be a JSON object",
        )
        assertInvalid("""{"type":"array","minItems":-1}""", "must be a non-negative integer")
        assertInvalid("""{"type":"array","maxItems":"3"}""", "must be a non-negative integer")
    }

    @Test
    fun stringAndNumberBoundsAreChecked() {
        assertInvalid("""{"type":"string","minLength":-1}""", "must be a non-negative integer")
        assertInvalid("""{"type":"string","maxLength":1.5}""", "must be a non-negative integer")
        assertInvalid("""{"type":"string","minLength":5,"maxLength":2}""", "minLength (5.0) must not be greater")
        assertInvalid("""{"type":"number","minimum":"x"}""", "must be a JSON number")
        assertInvalid("""{"type":"number","minimum":5,"maximum":1}""", "minimum (5.0) must not be greater")
        assertInvalid(
            """{"type":"number","exclusiveMinimum":5,"exclusiveMaximum":1}""",
            "exclusiveMinimum (5.0) must not be greater",
        )
    }

    @Test
    fun patternsMustBeBoundedCompilableAndNonCatastrophic() {
        val longPattern = "a".repeat(ToolSchema.MAX_PATTERN_LENGTH + 1)
        assertInvalid("""{"type":"string","pattern":"$longPattern"}""", "exceeds ${ToolSchema.MAX_PATTERN_LENGTH}")
        assertInvalid("""{"type":"string","pattern":"("}""", "does not compile")
        assertInvalid("""{"type":"string","pattern":1}""", "must be a string regex")
        // catastrophic backtracking shapes are rejected
        assertInvalid("""{"type":"string","pattern":"(a+)+"}""", "nested unbounded quantifier")
        assertInvalid("""{"type":"string","pattern":"(.*)*b"}""", "nested unbounded quantifier")
        // bounded repetition (in either position) stays legal
        assertTrue(violations("""{"type":"string","pattern":"([a-z]{1,3})*"}""").isEmpty())
        assertTrue(violations("""{"type":"string","pattern":"(\\d+){2}"}""").isEmpty())
        assertTrue(violations("""{"type":"string","pattern":"^/\\d{1,3}\\.\\d{1,3}"}""").isEmpty())
        assertTrue(violations("""{"type":"string","pattern":"a+b"}""").isEmpty())
    }

    @Test
    fun nestedSubschemasAreRecursivelyChecked() {
        assertInvalid(
            """
            {"type":"object","properties":{"a":{"type":"array","items":
            {"type":"object","properties":{"b":{"type":"string","const":1}}}}}}
            """,
            "unknown keyword 'const'",
        )
        assertInvalid(
            """{"type":"object","additionalProperties":{"type":"string","format":"uri"}}""",
            "unknown keyword 'format'",
        )
    }
}
