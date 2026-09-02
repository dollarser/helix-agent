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
     * The origin's SECURITY-RELEVANT canonical form (ADR-0011, HXA-042): the stable string
     * the [ToolDescriptor.contractHash] hashes over. For [McpOrigin] this is the server it is
     * bound to and the negotiated protocol version (a server update that changes either
     * invalidates prior approvals). [McpOrigin.serverProvidedHints] are EXCLUDED on purpose:
     * they are untrusted, display-only server text that is never consumed for classification,
     * risk or policy (see its KDoc) — folding untrusted display hints into the approval
     * contract would let a server revoke an approval by editing a hint, which is not a
     * security property.
     */
    fun canonicalOf(): String =
        when (this) {
            is BuiltInOrigin -> "built-in"
            is McpOrigin -> "mcp:$serverId:$protocolVersion"
        }

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
