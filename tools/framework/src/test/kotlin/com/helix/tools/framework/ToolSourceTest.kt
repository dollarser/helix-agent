package com.helix.tools.framework

import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.TestFixtures.a2aSpec
import com.helix.tools.framework.TestFixtures.builtIn
import com.helix.tools.framework.TestFixtures.mcpSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSourceTest {
    @Test
    fun `A2A source uses a distinct namespace and exact snapshot provenance`() {
        val descriptor = A2aToolSource("travel-agent", listOf(a2aSpec())).load().single()

        assertEquals(RemoteToolNames.a2a("travel-agent", "echo-skill").value, descriptor.name.value)
        assertEquals(ToolOperationClass.NETWORK, descriptor.operationClass)
        val origin = descriptor.origin as ToolOrigin.A2aOrigin
        assertEquals("travel-agent", origin.agentId)
        assertEquals("echo-skill", origin.skillId)
        assertEquals("b".repeat(64), origin.cardHash)
    }

    @Test
    fun `A2A source cannot impersonate MCP and snapshot hash changes contract`() {
        val first = A2aToolSource("agent", listOf(a2aSpec())).load().single()
        val changed = A2aToolSource("agent", listOf(a2aSpec(skillHash = "d".repeat(64)))).load().single()

        assertNotEquals(first.contractHash, changed.contractHash)
        assertThrows(IllegalArgumentException::class.java) {
            ToolDescriptor(
                name = ToolName("mcp_agent_echo"),
                version = first.version,
                description = first.description,
                inputSchema = first.inputSchema,
                outputSchema = first.outputSchema,
                operationClass = first.operationClass,
                timeout = first.timeout,
                maxOutputBytes = first.maxOutputBytes,
                requiredCapabilities = first.requiredCapabilities,
                idempotency = first.idempotency,
                executionTarget = first.executionTarget,
                origin = first.origin,
            )
        }
    }

    @Test
    fun mcpSourceUsesPortableSourceQualifiedNames() {
        val source = McpToolSource("wikipedia", "2025-03-26", listOf(mcpSpec("search")))
        val descriptor = source.load().single()
        assertEquals(RemoteToolNames.mcp("wikipedia", "search").value, descriptor.name.value)
        val origin = descriptor.origin
        assertTrue(origin is ToolOrigin.McpOrigin)
        assertEquals("wikipedia", (origin as ToolOrigin.McpOrigin).serverId)
        assertEquals("2025-03-26", origin.protocolVersion)
    }

    @Test
    fun mcpServerHintsAreStoredButNeverLowerTheClass() {
        // The server claims read-only; Helix's classification says NETWORK.
        // The hint is recorded for audit and the class stays NETWORK — the
        // annotation cannot reclassify the tool (doc 02 section 7 / doc 10 section 4.4).
        val source =
            McpToolSource(
                "srv",
                "2025-03-26",
                listOf(
                    mcpSpec(
                        "search",
                        operationClass = ToolOperationClass.NETWORK,
                        hints =
                            mapOf("readOnlyHint" to true),
                    ),
                ),
            )
        val descriptor = source.load().single()
        assertEquals(ToolOperationClass.NETWORK, descriptor.operationClass)
        val origin = descriptor.origin as ToolOrigin.McpOrigin
        assertEquals(mapOf("readOnlyHint" to true), origin.serverProvidedHints)
    }

    @Test
    fun mcpSourceRejectsReadOnlyAndMetadataClassifications() {
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource(
                "srv",
                "2025-03-26",
                listOf(
                    mcpSpec(
                        "search",
                        operationClass = ToolOperationClass.READ_ONLY,
                        hints =
                            mapOf("readOnlyHint" to true),
                    ),
                ),
            )
        }
        // METADATA is built-in-only: an MCP spec must not declare it either.
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource(
                "srv",
                "2025-03-26",
                listOf(mcpSpec("search", operationClass = ToolOperationClass.METADATA)),
            )
        }
    }

    @Test
    fun mcpSourceValidatesServerIdAndToolNameSegments() {
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("", "1", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("x".repeat(65), "1", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("-srv", "1", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("sr.v", "1", emptyList()) }
        val dotted = McpToolSource("srv", "1", listOf(mcpSpec("to.ol"))).load().single()
        assertEquals(RemoteToolNames.mcp("srv", "to.ol"), dotted.name)
        assertEquals(setOf("to.ol"), dotted.discovery.keywords)
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", "1", listOf(mcpSpec("x".repeat(129))))
        }
        McpToolSource("x".repeat(64), "1", listOf(mcpSpec("y".repeat(128))))
    }

    @Test
    fun mcpSourceRejectsDuplicateToolsWithinOneServer() {
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", "1", listOf(mcpSpec("search"), mcpSpec("search")))
        }
    }

    @Test
    fun mcpSourceRejectsTheSameToolNameTwiceInOneSnapshot() {
        // one server snapshot lists each tool name exactly once
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", "1", listOf(mcpSpec("search", version = 1), mcpSpec("search", version = 2)))
        }
    }

    @Test
    fun builtInSourceRejectsDuplicateNameVersionPairs() {
        // two descriptors with the same (name, version)
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolSource(listOf(builtIn(name = "read"), builtIn(name = "read")))
        }
        // the same name at a different version is a legal evolution
        val source = BuiltInToolSource(listOf(builtIn(name = "read", version = 1), builtIn(name = "read", version = 2)))
        assertEquals(2, source.load().size)
        assertEquals(ToolSourceKind.BUILT_IN, source.kind)
    }
}
