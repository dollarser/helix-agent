package com.helix.app.mcp

import com.helix.core.model.ToolName
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseToolDiscoveryTest {
    private val registry = ToolRegistry()
    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools.search")))
    private val origin = ToolOrigin.PluginOrigin("mobile-use", "0.2.0", "mobile-use")
    private val screenshot =
        search.copy(
            name = ToolName("ui.screenshot"),
            origin = origin,
            discoveryCompanions = setOf(ToolName("ui.device")),
        )
    private val device = search.copy(name = ToolName("ui.device"), origin = origin)

    init {
        listOf(screenshot, device).forEach { descriptor ->
            registry.register(
                descriptor,
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        error("Discovery must not execute")
                    }
                },
            )
        }
    }

    @Test fun screenshotSearchLoadsItsFrameContractInOnlyTheRequestingConversation() {
        discovery.visible("selected", registry.all())
        assertEquals(listOf(screenshot), discovery.search("selected", "ui.screenshot", 1))
        assertEquals(setOf(search, screenshot, device), discovery.visible("selected", registry.all()).toSet())
        assertEquals(listOf(search), discovery.visible("other", registry.all()))
    }

    @Test fun disabledOrRevokedFrameContractCannotBeLoadedAsADependency() {
        var allowed = true
        val filtered = McpToolDiscovery(registry) { _, tool -> allowed || tool != device }
        filtered.search("selected", "ui.screenshot", 1)
        assertTrue(device in filtered.visible("selected", registry.all()))
        allowed = false
        assertFalse(device in filtered.visible("selected", registry.all()))
        filtered.search("selected", "ui.screenshot", 1)
        assertFalse(device in filtered.visible("selected", registry.all()))
    }

    @Test fun dependencyCannotEscapeTheAdmittedRequestSnapshot() {
        discovery.visible("selected", listOf(search, screenshot))
        discovery.search("selected", "ui.screenshot", 1)
        assertEquals(setOf(search, screenshot), discovery.visible("selected", listOf(search, screenshot)).toSet())
    }

    @Test fun frameDependencyNeverBorrowsAnotherPluginOrRuntimeBinding() {
        for (foreign in listOf(
            ToolOrigin.PluginOrigin("other", "0.2.0", "mobile-use"),
            ToolOrigin.PluginOrigin("mobile-use", "0.2.0", "other-runtime"),
            ToolOrigin.PluginOrigin("mobile-use", "0.3.0", "mobile-use"),
        )) {
            assertEquals(
                listOf(screenshot),
                ToolDiscoveryWorkset.complete(listOf(screenshot), listOf(screenshot, device.copy(origin = foreign))),
            )
        }
    }
}
