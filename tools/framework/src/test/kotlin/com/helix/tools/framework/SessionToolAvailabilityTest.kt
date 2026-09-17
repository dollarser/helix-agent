package com.helix.tools.framework

import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.policy.effectiveAvailability
import com.helix.tools.framework.ToolOrigin.A2aOrigin
import com.helix.tools.framework.ToolOrigin.BuiltInOrigin
import com.helix.tools.framework.ToolOrigin.McpOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * HXA-209 D7: the canonical tool identity (ADR-PERMISSIONS-001 section 1.1) — the
 * `ToolOrigin.canonicalOf()` form the execution entry and every exposure surface key the
 * two-state availability on. Disabling covers exactly ONE trusted identity: a same-named tool
 * from a different source must never share it, and an MCP/A2A source update that changes any
 * bound field yields a NEW identity the old disable does not follow. The end-to-end fold
 * proves the DERIVED identity is what scopes a disable — closing the loop the app-level
 * availability test leaves open, since it feeds a hand-written sourceRef string rather than
 * the one [ToolOrigin.canonicalOf] derives.
 */
class SessionToolAvailabilityTest {
    private val schemaA = "0".repeat(64)
    private val schemaB = "f".repeat(64)

    @Test
    fun `a built-in identity is the stable built-in marker`() {
        assertEquals("built-in", BuiltInOrigin.canonicalOf())
    }

    @Test
    fun `an mcp identity binds the server, protocol and schema`() {
        val origin = McpOrigin(serverId = "srv", protocolVersion = "2025-06-18", sourceSchemaHash = schemaA)
        assertEquals("mcp:srv:2025-06-18:$schemaA", origin.canonicalOf())
    }

    @Test
    fun `an a2a identity binds every provenance field`() {
        val origin =
            A2aOrigin(
                agentId = "agent",
                skillId = "skill",
                interfaceOrigin = "local",
                binding = "JSONRPC",
                protocolVersion = "1.0",
                cardHash = schemaA,
                skillHash = schemaB,
            )
        assertEquals("a2a:agent:skill:local:JSONRPC:1.0:$schemaA:$schemaB", origin.canonicalOf())
    }

    @Test
    fun `same name from different sources has different identities`() {
        // the identity is (trusted source, name), never the bare name: a built-in and an MCP
        // tool both named "shell" are distinct, as are two MCP servers both exposing "shell"
        val builtInShell = BuiltInOrigin.canonicalOf()
        val mcpShell = McpOrigin(serverId = "srv", protocolVersion = "1", sourceSchemaHash = schemaA).canonicalOf()
        assertNotEquals(builtInShell, mcpShell)
        val serverAShell = McpOrigin(serverId = "srvA", protocolVersion = "1", sourceSchemaHash = schemaA).canonicalOf()
        val serverBShell = McpOrigin(serverId = "srvB", protocolVersion = "1", sourceSchemaHash = schemaA).canonicalOf()
        assertNotEquals(serverAShell, serverBShell)
    }

    @Test
    fun `an mcp schema update yields a new identity`() {
        val before = McpOrigin(serverId = "srv", protocolVersion = "1", sourceSchemaHash = schemaA)
        val after = McpOrigin(serverId = "srv", protocolVersion = "1", sourceSchemaHash = schemaB)
        // same server + same tool name, but the bound schema changed -> a different identity,
        // so a disable of the pre-update tool cannot follow it across the update
        assertNotEquals(before.canonicalOf(), after.canonicalOf())
    }

    @Test
    fun `a disable is scoped to the derived identity and never bleeds to a same-name other source`() {
        val serverA = McpOrigin(serverId = "srvA", protocolVersion = "1", sourceSchemaHash = schemaA)
        val serverB = McpOrigin(serverId = "srvB", protocolVersion = "1", sourceSchemaHash = schemaB)
        val toolName = "shell"
        // the store keys on the DERIVED identity (source, name); only serverA's is disabled
        val stored = mutableMapOf<Pair<String, String>, ToolAvailabilityStates>()
        stored[Pair(serverA.canonicalOf(), toolName)] =
            ToolAvailabilityStates(global = ToolAvailabilityState.DISABLED)
        assertEquals(ToolAvailabilityState.DISABLED, fold(stored, serverA.canonicalOf(), toolName))
        // serverB's same-named tool resolves through its OWN identity: untouched, so ENABLED
        assertEquals(ToolAvailabilityState.ENABLED, fold(stored, serverB.canonicalOf(), toolName))
    }

    /**
     * The single fold every surface uses (outer disable wins, else narrowest explicit, default
     * ENABLED), over the states stored under the derived identity — the same shape as the
     * dispatcher's execution-entry read.
     */
    private fun fold(
        stored: Map<Pair<String, String>, ToolAvailabilityStates>,
        identity: String,
        toolName: String,
    ): ToolAvailabilityState {
        val states = stored[Pair(identity, toolName)] ?: ToolAvailabilityStates()
        return effectiveAvailability(states.global, states.workspace, states.session)
    }
}
