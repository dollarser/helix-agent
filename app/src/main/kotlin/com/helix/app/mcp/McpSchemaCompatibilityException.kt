package com.helix.app.mcp

/** No remote schema content is exposed in the UI-facing failure category. */
internal class McpSchemaCompatibilityException : IllegalArgumentException("MCP schema is outside the supported subset")
