package com.helix.app.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class McpToolDiscoveryTest {
    private val registry = ToolRegistry()
    private val implementations = ToolImplementationRegistry()
    private val discovery = McpToolDiscovery(registry).also { it.register(implementations) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools.search")))

    private fun remote(index: Int): ToolDescriptor =
        search.copy(
            name = ToolName("mcp.catalog.tool_$index"),
            description = "catalog operation $index",
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L1,
            origin = ToolOrigin.McpOrigin("catalog", "2025-03-26", "a".repeat(64)),
        )

    private fun catalog(count: Int = 500) = registry.replaceMcpServer("catalog", (0 until count).map(::remote))

    @Test
    fun lateCatalogToolIsDiscoverableWithoutExposingAllSchemas() {
        catalog()
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        val found = discovery.search("session", "tool_499", 8)
        assertEquals(listOf("mcp.catalog.tool_499"), found.map { it.name.value })
        assertEquals(listOf(search) + found, discovery.visible("session", registry.all()))
        assertEquals(listOf(search), discovery.visible("other", registry.all()))
    }

    @Test
    fun newSearchReplacesWindowAndNeverGrowsBeyondSixteen() {
        catalog()
        assertEquals(16, discovery.search("session", "catalog", 16).size)
        discovery.search("session", "tool_499", 1)
        assertEquals(2, discovery.visible("session", registry.all()).size)
        discovery.search("session", "no match", 8)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }

    @Test
    @Suppress("MaxLineLength") // Exact schema fixture.
    fun schemaReplacementInvalidatesExposureAndContractsWithoutExpandingAuthority() {
        val old = catalog()
        val selected = discovery.search("session", "tool_499", 1).single()
        val changed =
            selected.copy(
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"changed":{"type":"boolean"}},"additionalProperties":false}""",
                        ).jsonObject,
            )
        registry.replaceMcpServer("catalog", old.map { if (it == selected) changed else it })
        assertFalse(selected.contractHash == changed.contractHash)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertEquals(listOf(changed), discovery.search("session", "tool_499", 1))
        registry.replaceMcpServer("catalog", emptyList())
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertTrue(discovery.search("session", "catalog", 8).isEmpty())
    }

    @Test
    fun modeAdmissionStillRemovesPreviouslyDiscoveredRemoteTool() {
        catalog()
        discovery.search("session", "tool_499", 1)
        assertEquals(listOf(search), discovery.visible("session", listOf(search)))
    }

    @Test
    fun smallCatalogRemainsAvailableAndRestartDropsLargeCatalogWindow() {
        catalog(3)
        assertEquals(4, discovery.visible("session", registry.all()).size)
        catalog()
        discovery.search("session", "tool_499", 1)
        assertEquals(listOf(search), McpToolDiscovery(registry).visible("session", registry.all()))
    }

    @Test
    fun cancelledSearchCannotLoadSchemas() {
        catalog()
        val call =
            ExecutableToolCall(
                "call",
                "tools.search",
                "1",
                Json.parseToJsonElement("""{"query":"tool_499"}""").jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                object : CancelSignal {
                    override fun isCancelled() = true
                },
                "session",
            )
        assertEquals(ToolExecutorResult.Cancelled, implementations.resolve(search.name, ToolVersion(1)).execute(call))
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }
}
