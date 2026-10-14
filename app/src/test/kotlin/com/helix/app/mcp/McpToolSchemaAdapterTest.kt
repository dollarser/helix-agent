package com.helix.app.mcp

import com.helix.extensions.mcp.McpToolMetadata
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSchemaAdapterTest {
    private val schema =
        Json.parseToJsonElement(
            """{"properties":{"query":{"type":"string"}},"required":["query"],"type":"object"}""",
        ) as JsonObject

    private fun metadata(input: JsonObject) =
        McpToolMetadata("search", null, null, input, null, "a".repeat(64), emptyMap())

    @Test
    fun defaultDialectFormatAnnotationDoesNotRemoveValidationConstraintsOrLiteralData() {
        val raw =
            Json.parseToJsonElement(
                """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                "properties":{"format":{"type":"string","format":"date","minLength":10},
                "literal":{"enum":[{"format":"keep-me"}]}},"required":["format"]}""",
            ) as JsonObject
        val adapted = McpToolSchemaAdapter.adapt(metadata(raw))
        assertEquals("a".repeat(64), adapted.schemaHash)
        assertTrue(adapted.inputSchema.toString().contains("keep-me"))
        assertFalse(adapted.inputSchema.toString().contains("date"))
        assertTrue(
            ToolSchemaValidator.validate(adapted.inputSchema, JsonObject(emptyMap())) is ToolSchemaValidation.Invalid,
        )
        assertTrue(
            ToolSchemaValidator.validate(
                adapted.inputSchema,
                JsonObject(mapOf("format" to JsonPrimitive("short"))),
            ) is ToolSchemaValidation.Invalid,
        )
    }

    @Test
    fun recognizedDialectPreservesConstraintsAndOriginalSourceHash() {
        val raw = JsonObject(schema + ("\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema")))
        val original = metadata(raw)
        val adapted = McpToolSchemaAdapter.adapt(original)
        assertEquals(original.schemaHash, adapted.schemaHash)
        assertEquals(schema, adapted.inputSchema)
        assertTrue(original.inputSchema.containsKey("\$schema"))
        assertFalse(adapted.inputSchema.containsKey("\$schema"))
        assertTrue(
            ToolSchemaValidator.validate(adapted.inputSchema, JsonObject(emptyMap())) is ToolSchemaValidation.Invalid,
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(adapted.inputSchema, JsonObject(mapOf("query" to JsonPrimitive("docs")))),
        )
        assertEquals(metadata(schema), McpToolSchemaAdapter.adapt(metadata(schema)))
    }

    @Test
    fun unknownDialectsAndUnsupportedConstraintsAreNotSilentlyRemoved() {
        val dialect = "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema")
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSchemaAdapter.adapt(
                metadata(
                    JsonObject(schema + ("\$schema" to JsonPrimitive("https://unknown.invalid/schema"))),
                ),
            )
        }
        for (extra in listOf(
            "\$ref" to JsonPrimitive("#/$"),
            "unevaluatedProperties" to JsonPrimitive(false),
            "format" to JsonPrimitive(false),
            "\$vocabulary" to
                JsonObject(
                    mapOf("https://json-schema.org/draft/2020-12/vocab/format-assertion" to JsonPrimitive(true)),
                ),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                McpToolSchemaAdapter.adapt(metadata(JsonObject(schema + dialect + extra)))
            }
        }
        val nested =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf("query" to JsonObject(mapOf(dialect)))),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSchemaAdapter.adapt(metadata(JsonObject(nested + dialect)))
        }
    }
}
