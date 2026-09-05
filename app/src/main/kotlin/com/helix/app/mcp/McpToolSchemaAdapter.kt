package com.helix.app.mcp

import com.helix.extensions.mcp.McpToolMetadata
import com.helix.tools.framework.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Translates one known dialect declaration into the existing ToolSchema subset, without dropping constraints. */
internal object McpToolSchemaAdapter {
    fun adapt(tool: McpToolMetadata): McpToolMetadata {
        val declaration = tool.inputSchema["\$schema"] ?: return tool
        require(declaration == JsonPrimitive("https://json-schema.org/draft/2020-12/schema")) {
            "MCP_UNSUPPORTED_SCHEMA_DIALECT"
        }
        val schema = JsonObject(tool.inputSchema - "\$schema")
        require(ToolSchema.check(schema).isEmpty()) { "MCP_SCHEMA_OUTSIDE_TOOL_SUBSET" }
        // Keep the source schema hash intact: approval/checkpoint bindings still detect remote changes.
        return tool.copy(inputSchema = schema)
    }
}
