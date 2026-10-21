package com.helix.app.proot

import com.helix.app.mcp.McpToolDiscovery
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogDiscoveryTest {
    private val registry = ToolRegistry()
    private val reader = ProotLogTool.descriptor()
    private val executor =
        object : ToolExecutor {
            override fun execute(call: com.helix.tools.framework.ExecutableToolCall) =
                ToolExecutorResult.Completed(buildJsonObject {})
        }

    init {
        registry.register(reader, executor)
        registry.register(
            reader.copy(
                name =
                    com.helix.core.model
                        .ToolName("code.linux.run"),
                description = "Run commands",
            ),
            executor,
        )
        registry.register(
            reader.copy(
                name =
                    com.helix.core.model
                        .ToolName("root.logs"),
                description = "Read system logs",
            ),
            executor,
        )
    }

    @Test fun originalLogsRankFirstAcrossConciseBilingualQueriesAndExactNames() {
        val discovery = McpToolDiscovery(registry)
        listOf("读取 日志", "失败 日志", "original call logs", "captured stderr", reader.name.value).forEach { query ->
            assertEquals(query, reader, discovery.search("allowed", query, 8).firstOrNull())
        }
    }

    @Test fun catalogSourceIsDistinctFromExecutionLogSource() {
        val discovery = McpToolDiscovery(registry).also { it.register(registry) }
        assertEquals(
            emptyList<com.helix.tools.framework.ToolDescriptor>(),
            discovery.search("s", reader.name.value, 8, source = "saved"),
        )
        assertEquals(listOf(reader), discovery.search("s", reader.name.value, 8, source = "helix"))
        val contract =
            requireNotNull(
                registry.resolveLatest(
                    com.helix.core.model
                        .ToolName("tools.search"),
                ),
            )
        val source = contract.inputSchema.toString()
        org.junit.Assert.assertTrue(source.contains("catalog owner filter") && source.contains("saved/live"))
    }

    @Test fun deniedReaderNeverReturnsOrStaysLoadedAfterAvailabilityChanges() {
        var enabled = true
        val discovery = McpToolDiscovery(registry) { _, tool -> tool != reader || enabled }
        discovery.visible("s", registry.all())
        assertEquals(listOf(reader), discovery.search("s", reader.name.value, 8))
        enabled = false
        listOf("读取 日志", "original call logs", reader.name.value).forEach { query ->
            assertFalse(discovery.search("s", query, 8).contains(reader))
        }
        assertFalse(discovery.visible("s", registry.all()).contains(reader))
        enabled = true
        discovery.visible("s", registry.all())
        assertEquals(listOf(reader), discovery.search("s", reader.name.value, 8))
    }
}
