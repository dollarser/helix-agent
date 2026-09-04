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
            is BuiltInOrigin -> {
                "built-in"
            }

            is McpOrigin -> {
                "mcp:$serverId:$protocolVersion:$sourceSchemaHash"
            }

            is A2aOrigin -> {
                "a2a:$agentId:$skillId:$interfaceOrigin:$binding:$protocolVersion:$cardHash:$skillHash"
            }
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
        val protocolVersion: String,
        val sourceSchemaHash: String,
        val serverProvidedHints: Map<String, Boolean> = emptyMap(),
    ) : ToolOrigin {
        init {
            require(protocolVersion.length in 1..32 && protocolVersion.all { it.code in 0x21..0x7e }) {
                "MCP protocol version must be 1..32 visible ASCII characters"
            }
            require(sourceSchemaHash.length == 64 && sourceSchemaHash.all { it in '0'..'9' || it in 'a'..'f' }) {
                "MCP source schema hash must be lowercase SHA-256"
            }
        }
    }

    /** Exact remote Agent/Skill snapshot provenance; all fields participate in contractHash. */
    @Suppress("LongParameterList")
    data class A2aOrigin(
        val agentId: String,
        val skillId: String,
        val interfaceOrigin: String,
        val binding: String,
        val protocolVersion: String,
        val cardHash: String,
        val skillHash: String,
    ) : ToolOrigin {
        init {
            require(agentId.isNotBlank() && agentId.length <= 64) { "A2A agent id is invalid" }
            require(skillId.isNotBlank() && skillId.length <= 256) { "A2A Skill id is invalid" }
            require(interfaceOrigin.isNotBlank() && interfaceOrigin.length <= 2_048) { "A2A origin is invalid" }
            require(binding == "JSONRPC" || binding == "HTTP+JSON") { "A2A binding is unsupported" }
            require(protocolVersion == "1.0") { "A2A protocol version is unsupported" }
            requireSha256(cardHash, "A2A Agent Card hash")
            requireSha256(skillHash, "A2A Skill hash")
        }

        private fun requireSha256(
            value: String,
            label: String,
        ) {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "$label must be lowercase SHA-256"
            }
        }
    }
}
