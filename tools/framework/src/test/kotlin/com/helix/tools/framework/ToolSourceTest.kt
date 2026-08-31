package com.helix.tools.framework

import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.TestFixtures.builtIn
import com.helix.tools.framework.TestFixtures.mcpSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSourceTest {
    @Test
    fun mcpSourceForcesTheMcpDotServerDotToolNaming() {
        val source = McpToolSource("wikipedia", 1, listOf(mcpSpec("search")))
        val descriptor = source.load().single()
        assertEquals("mcp.wikipedia.search", descriptor.name.value)
        val origin = descriptor.origin
        assertTrue(origin is ToolOrigin.McpOrigin)
        assertEquals("wikipedia", (origin as ToolOrigin.McpOrigin).serverId)
        assertEquals(1, origin.protocolVersion)
    }

    @Test
    fun mcpServerHintsAreStoredButNeverLowerTheClass() {
        // The server claims read-only; Helix's classification says NETWORK.
        // The hint is recorded for audit and the class stays NETWORK — the
        // annotation cannot reclassify the tool (doc 02 section 7 / doc 10 section 4.4).
        val source =
            McpToolSource(
                "srv",
                1,
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
    fun mcpSourceRejectsAReadOnlyClassification() {
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource(
                "srv",
                1,
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
    }

    @Test
    fun mcpSourceValidatesServerIdAndToolNameSegments() {
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("", 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("x".repeat(65), 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("-srv", 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpToolSource("sr.v", 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", 1, listOf(mcpSpec("to.ol")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", 1, listOf(mcpSpec("x".repeat(65))))
        }
        // the boundary lengths are accepted: 4 ("mcp.") + 64 + 1 + 59 = 128,
        // exactly the ToolName total-length limit
        McpToolSource("x".repeat(64), 1, listOf(mcpSpec("y".repeat(59))))
    }

    @Test
    fun mcpSourceRejectsDuplicateToolsWithinOneServer() {
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", 1, listOf(mcpSpec("search"), mcpSpec("search")))
        }
    }

    @Test
    fun mcpSourceRejectsTheSameToolNameTwiceInOneSnapshot() {
        // one server snapshot lists each tool name exactly once
        assertThrows(IllegalArgumentException::class.java) {
            McpToolSource("srv", 1, listOf(mcpSpec("search", version = 1), mcpSpec("search", version = 2)))
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
