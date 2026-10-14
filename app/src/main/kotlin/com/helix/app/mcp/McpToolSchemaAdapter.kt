package com.helix.app.mcp

import com.helix.extensions.mcp.McpToolMetadata
import com.helix.tools.framework.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Translates one known dialect declaration into the existing ToolSchema subset, without dropping constraints. */
internal object McpToolSchemaAdapter {
    fun adapt(tool: McpToolMetadata): McpToolMetadata {
        val declaration = tool.inputSchema["\$schema"] ?: return tool
        if (declaration != JsonPrimitive("https://json-schema.org/draft/2020-12/schema")) {
            throw McpSchemaCompatibilityException()
        }
        val schema = normalizeAnnotations(JsonObject(tool.inputSchema - "\$schema"))
        if (ToolSchema.check(schema).isNotEmpty()) throw McpSchemaCompatibilityException()
        // Keep the source schema hash intact: approval/checkpoint bindings still detect remote changes.
        return tool.copy(inputSchema = schema)
    }

    // The exact 2020-12 default dialect uses format-annotation, not format-assertion.
    // Visit only schema positions: a property named "format" and literal enum values are data.
    private fun normalizeAnnotations(schema: JsonObject): JsonObject {
        val format = schema["format"]
        if (format != null && (format !is JsonPrimitive || !format.isString)) {
            throw McpSchemaCompatibilityException()
        }
        return JsonObject(
            (schema - "format").mapValues { (key, value) ->
                when {
                    key == "properties" && value is JsonObject -> {
                        JsonObject(
                            value.mapValues { (_, child) ->
                                if (child is JsonObject) normalizeAnnotations(child) else child
                            },
                        )
                    }

                    key in setOf("items", "additionalProperties") && value is JsonObject -> {
                        normalizeAnnotations(value)
                    }

                    else -> {
                        value
                    }
                }
            },
        )
    }
}
