package com.helix.app.mcp

import com.helix.core.model.ToolName
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDiscoveryCompanionsTest {
    private val registry = ToolRegistry().also { McpToolDiscovery(it).register(it) }
    private val base = requireNotNull(registry.resolveLatest(ToolName("tools.search")))
    private val origin = ToolOrigin.PluginOrigin("new-plugin", "1", "native")

    private fun tool(
        name: String,
        vararg companions: String,
    ) = base.copy(
        name = ToolName(name),
        origin = origin,
        discoveryCompanions = companions.map(::ToolName).toSet(),
    )

    @Test fun arbitraryPluginExpandsTransitiveCycleOnceInStableOrder() {
        val action = tool("sample.act", "sample.observe")
        val observe = tool("sample.observe", "sample.frame")
        val frame = tool("sample.frame", "sample.act")
        assertEquals(
            listOf(action, observe, frame),
            ToolDiscoveryWorkset.complete(listOf(action), listOf(frame, observe, action)),
        )
    }

    @Test fun unavailableMatchesAndCompanionsCannotEnterTheSnapshot() {
        val action = tool("sample.act", "sample.observe")
        val observe = tool("sample.observe")
        assertEquals(emptyList<Any>(), ToolDiscoveryWorkset.complete(listOf(action), listOf(observe)))
        assertEquals(listOf(action), ToolDiscoveryWorkset.complete(listOf(action), listOf(action)))
        val replacement = action.copy(description = "Replacement implementation contract")
        assertEquals(emptyList<Any>(), ToolDiscoveryWorkset.complete(listOf(action), listOf(replacement, observe)))
    }

    @Test fun missingCompanionDoesNotBorrowAnotherOriginOrActivateIt() {
        val action = tool("sample.act", "sample.observe")
        val foreign = tool("sample.observe").copy(origin = origin.copy(pluginVersion = "2"))
        assertEquals(listOf(action), ToolDiscoveryWorkset.complete(listOf(action), listOf(action, foreign)))
    }

    @Test fun exposureBudgetKeepsTheCompleteCompanionGroupOrOmitsIt() {
        val recent = tool("sample.act", "sample.observe")
        val observe = tool("sample.observe")
        val huge =
            tool("sample.huge").copy(
                inputSchema =
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        put("description", kotlinx.serialization.json.JsonPrimitive("x".repeat(70_000)))
                    },
            )
        val available = listOf(huge, recent, observe)
        assertEquals(listOf(recent, observe), ToolDiscoveryWorkset.retain(emptyList(), listOf(recent), available))
        assertEquals(listOf(huge), ToolDiscoveryWorkset.retain(listOf(huge), listOf(recent), available))
    }
}
