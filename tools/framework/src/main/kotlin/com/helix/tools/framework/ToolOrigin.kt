package com.helix.tools.framework

/**
 * Where a tool's registration comes from (architecture doc section 7.1).
 *
 * The origin is registry data, not a trust signal by itself: it determines
 * namespace rules (built-in names vs `mcp.*` names) and what provenance
 * facts are persisted with the descriptor.
 */
sealed interface ToolOrigin {
    /**
     * A built-in tool: a fixed name registered in product code. Built-in
     * names can never be registered from model output or by an MCP server
     * (doc 02 section 7.1: 内置工具名不能由模型动态注册).
     */
    data object BuiltInOrigin : ToolOrigin

    /**
     * A tool exposed by a connected, user-enabled MCP server (doc 10 section
     * 4.3): the descriptor is bound to the server it came from.
     *
     * [protocolVersion] is the MCP protocol version the server negotiated
     * (a schema change after a server update invalidates all approvals —
     * the approval hash includes [ToolDescriptor.schemaHash] and the version
     * of the bound descriptor).
     *
     * [serverProvidedHints] records the server's own annotations
     * (`readOnlyHint`, `destructiveHint`, ...). They are UNTRUSTED TEXT from
     * the server (doc 10 section 4.4): stored for audit/display ONLY and
     * never consumed for classification, risk or policy.
     */
    data class McpOrigin(
        val serverId: String,
        val protocolVersion: Int,
        val serverProvidedHints: Map<String, Boolean> = emptyMap(),
    ) : ToolOrigin {
        init {
            require(protocolVersion >= 1) { "MCP protocol version must be >= 1" }
        }
    }
}
