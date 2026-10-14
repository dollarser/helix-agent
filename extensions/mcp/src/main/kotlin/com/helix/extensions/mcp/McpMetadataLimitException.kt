package com.helix.extensions.mcp

/** A local metadata budget failure, with no remote content in the error message. */
class McpMetadataLimitException : IllegalArgumentException("MCP metadata exceeds the local size limit")
