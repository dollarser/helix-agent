package com.helix.app

import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.AgentMode
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolDiscoveryDeviceTest {
    @Test
    @Suppress("LongMethod") // One production dispatcher flow and guaranteed registry cleanup.
    fun searchExposesOnlyMatchingEnabledSchemasAndDisableDropsThem() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val pipeline = container.toolPipeline
        val search = requireNotNull(pipeline.resolveLatest("tools.search"))
        val server = "discoveryfixture"
        val session = "catalog-${System.nanoTime()}"
        val turn = "catalog-turn-${System.nanoTime()}"
        val chat = container.chatService
        container.storage.sessions.create(session, "Catalog test", null, null, System.currentTimeMillis())
        container.storage.turns.start(turn, session, System.currentTimeMillis())
        chat.openSession(session)
        val catalog =
            (0 until 500).map { index ->
                search.copy(
                    name = ToolName("mcp.$server.tool_$index"),
                    description = "catalog operation $index",
                    operationClass = ToolOperationClass.NETWORK,
                    baseRisk = RiskLevel.L1,
                    origin = ToolOrigin.McpOrigin(server, "2025-03-26", "a".repeat(64)),
                )
            }
        pipeline.registry.replaceMcpServer(server, catalog)
        try {
            val result =
                chat.dispatchToolCall(
                    turn,
                    turn,
                    "tools.search",
                    """{"query":"tool_499"}""",
                    mode = AgentMode.ACT,
                )
            assertTrue(result.toString(), result is ToolDispatchOutcome.Succeeded)
            val visible =
                pipeline.mcpDiscovery
                    .visible(session, pipeline.registry.all())
                    .filter { it.origin is ToolOrigin.McpOrigin }
            assertEquals(listOf("mcp.$server.tool_499"), visible.map { it.name.value })
            pipeline.registry.replaceMcpServer(server, emptyList())
            assertTrue(pipeline.mcpDiscovery.visible(session, pipeline.registry.all()).none { it in catalog })
        } finally {
            pipeline.registry.replaceMcpServer(server, emptyList())
            pipeline.endTurn(turn)
            chat.closeSession()
        }
    }
}
