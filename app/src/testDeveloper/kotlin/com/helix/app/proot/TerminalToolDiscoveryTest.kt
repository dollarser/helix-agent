package com.helix.app.proot

import com.helix.app.application.tools.ToolModelSchemaProjection
import com.helix.app.mcp.McpToolDiscovery
import com.helix.app.mcp.ToolDiscoveryWorkset
import com.helix.app.plan.PlanTools
import com.helix.core.agent.ModelInputEstimate
import com.helix.core.model.ToolName
import com.helix.tools.browser.BrowserDownloadTool
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalToolDiscoveryTest {
    private val registry = ToolRegistry()
    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val run = LinuxRunTool.descriptor()
    private val catalog =
        listOf(
            run,
            DetachedJobTools.start(),
            DetachedJobTools.control(false),
            DetachedJobTools.control(true),
            DetachedJobTools.collect(),
            DetachedJobTools.await(),
            ProotLogTool.descriptor(),
            BrowserDownloadTool.descriptor(ToolOrigin.PluginOrigin("browser", "1", "browser")),
            PlanTools.descriptor(),
        )

    init {
        catalog.forEach { descriptor ->
            registry.register(
                descriptor,
                object : ToolExecutor {
                    override fun execute(call: com.helix.tools.framework.ExecutableToolCall) =
                        ToolExecutorResult.Completed(buildJsonObject {})
                },
            )
        }
    }

    @Test fun terminalIntentRanksExecutionFirstWhileKeepingComplementaryCapabilities() {
        val query = "terminal command execute"
        val selected = discovery.search("s", query, 8)
        assertEquals(run, selected.first())
        assertEquals(run, discovery.search("other", "终端 执行", 8).first())
        assertEquals(listOf(run), discovery.search("exact", run.name.value, 8))
        val visible = discovery.visible("s", registry.all())
        assertTrue(visible.containsAll(ToolDiscoveryWorkset.complete(selected, catalog)))
        assertTrue(visible.any { it.name.value == DetachedJobTools.START })
        assertTrue(visible.any { it.name.value == DetachedJobTools.COLLECT })
        // Historical substring selection, only for this frozen production-descriptor comparison.
        val oldMatches =
            catalog.filter { tool ->
                val fields =
                    tool.inputSchema["properties"]
                        ?.jsonObject
                        ?.keys
                        .orEmpty()
                        .joinToString(" ")
                val text = "${tool.name.value} ${tool.description} $fields".lowercase()
                query.split(' ').any { it in text }
            }
        val oldTokens = tokens(ToolDiscoveryWorkset.complete(oldMatches, catalog))
        val newTokens = tokens(ToolDiscoveryWorkset.complete(selected, catalog))
        println(
            "terminal_discovery oldMatches=${oldMatches.map { it.name.value }} " +
                "newMatches=${selected.map { it.name.value }} oldTokens=$oldTokens newTokens=$newTokens",
        )
        val costs =
            catalog
                .sortedByDescending(ToolDiscoveryWorkset::bytes)
                .map { "${it.name.value}:${ToolDiscoveryWorkset.bytes(it)}" }
        println("terminal_costs $costs")
    }

    @Test fun explicitBackgroundDiscoveryPreservesTheCompleteLifecycleAndRecentExecutionTool() {
        discovery.search("s", run.name.value, 1)
        val start = discovery.search("s", DetachedJobTools.START, 1).single()
        val visible = discovery.visible("s", registry.all())
        assertTrue(visible.containsAll(ToolDiscoveryWorkset.complete(listOf(start), catalog)))
        assertTrue(run in visible)
        assertFalse(run in discovery.visible("other", registry.all()))
        assertTrue(visible.any { it.name == ToolName(DetachedJobTools.COLLECT) })
    }

    private fun tokens(tools: List<ToolDescriptor>): Long =
        ModelInputEstimate.of(emptyList(), tools.map(ToolModelSchemaProjection::project)).tools
}
