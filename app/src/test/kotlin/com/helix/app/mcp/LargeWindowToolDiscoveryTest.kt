package com.helix.app.mcp

import com.helix.app.application.tools.ToolModelSchemaProjection
import com.helix.core.agent.ContextCapacity
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.provider.api.ProviderContextSettings
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeWindowToolDiscoveryTest {
    private val registry = ToolRegistry()
    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools.search")))
    private val executor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall) = ToolExecutorResult.Completed(buildJsonObject {})
        }

    private fun register(tool: ToolDescriptor) = registry.register(tool, executor)

    private fun tool(
        name: String,
        description: String,
        origin: ToolOrigin = ToolOrigin.BuiltInOrigin,
    ) = search.copy(
        name = ToolName(name),
        description = description,
        inputSchema = buildJsonObject { put("type", "object") },
        origin = origin,
        operationClass = if (origin is ToolOrigin.McpOrigin) ToolOperationClass.NETWORK else search.operationClass,
    )

    @Test fun combinedTaskKeepsComplementaryCapabilitiesDespiteOneCompleteMatch() {
        val overview = tool("project.overview", "Report terminal browser workspace readiness")
        val terminal = tool("runtime.execute", "Run a terminal command")
        val browser = tool("browser.open", "Open a browser page")
        val files = tool("files.archive", "Archive the workspace")
        val tools = listOf(overview, terminal, browser, files)
        tools.forEach(::register)
        val matches = discovery.search("s", "terminal browser workspace", 16)
        assertEquals(overview, matches.first())
        assertEquals(tools.toSet(), matches.toSet())
        val visible = discovery.visible("s", registry.all())
        assertTrue(visible.containsAll(tools))
        assertFitsLargeWindow(visible)
    }

    @Test fun inflectedAndPartialNamesRemainReachableAlongsideWholeWordMatches() {
        val whole = tool("sample.connect", "Open a connection")
        val connector = tool("sample.connector", "Inspect an integration")
        val archive = tool("files.archive", "Package files")
        listOf(whole, connector, archive).forEach(::register)
        assertEquals(listOf(whole, connector), discovery.search("s", "connect", 16))
        assertEquals(listOf(archive), discovery.search("s", "archiv", 16))
        assertEquals(listOf(whole), discovery.search("s", "sample.connect", 16))
    }

    @Test fun largeDefaultSetAndRepeatedCapabilitySearchesDoNotImposeAGlobalToolCount() {
        val defaults = (1..160).map { tool("fixture.default_$it", "x".repeat(600)) }
        val lifecycle =
            listOf(
                tool("worker.start", "Start work").copy(
                    discoveryCompanions = setOf(ToolName("worker.status"), ToolName("worker.collect")),
                ),
                tool("worker.status", "Observe work"),
                tool("worker.collect", "Collect work"),
            )
        val capabilities =
            listOf(
                tool("files.archive", "Package workspace"),
                tool("browser.open", "Open a page"),
                tool("ui.inspect", "Inspect device screen"),
                tool(
                    "mcp.fixture.fetch",
                    "Fetch a record",
                    ToolOrigin.McpOrigin("fixture", "2025-03-26", "a".repeat(64)),
                ),
            )
        (defaults + lifecycle + capabilities).forEach(::register)
        val defaultNames = defaults.map { it.name.value }.toSet()
        discovery.visible("s", registry.all(), defaultNames)
        (capabilities + lifecycle.first()).forEach {
            assertEquals(listOf(it), discovery.search("s", it.name.value, 1))
        }
        val visible = discovery.visible("s", registry.all(), defaultNames)
        assertTrue(visible.containsAll(defaults + lifecycle + capabilities))
        assertTrue(visible.size > 160)
        assertFitsLargeWindow(visible)
        assertFalse(lifecycle.first() in discovery.visible("other", registry.all(), defaultNames))
        val admitted = registry.all().filterNot { it == capabilities.first() }
        assertFalse(capabilities.first() in discovery.visible("s", admitted, defaultNames))
        assertTrue(discovery.search("s", capabilities.first().name.value, 1).isEmpty())
    }

    @Test fun oversizedRecentSchemaStaysRediscoverableButItsStaticBudgetIsNotWindowAdaptive() {
        val large =
            tool(
                "mcp.large.inspect",
                "Inspect records",
                ToolOrigin.McpOrigin("large", "2025-03-26", "a".repeat(64)),
            ).copy(
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put("description", "x".repeat(80_556))
                    },
            )
        val small = tool("files.archive", "Archive files")
        listOf(large, small).forEach(::register)
        discovery.visible("s", registry.all())
        assertEquals(listOf(large), discovery.search("s", large.name.value, 1))
        assertTrue(large in discovery.visible("s", registry.all()))
        // Characterize the existing soft-cache limit honestly; a 256K window does not expand it.
        assertFitsLargeWindow(listOf(search, large, small))
        discovery.search("s", small.name.value, 1)
        assertFalse(large in discovery.visible("s", registry.all()))
        assertEquals(listOf(large), discovery.search("s", large.name.value, 1))
        assertTrue(large in discovery.visible("s", registry.all()))
    }

    private fun assertFitsLargeWindow(tools: List<ToolDescriptor>) {
        val request =
            TurnContextRequest(
                "fixture",
                List(2) { ModelMessage(ModelRole.USER, "x".repeat(200_000)) },
                tools.map(ToolModelSchemaProjection::project),
                16_384,
                ReasoningEffort.OFF,
            )
        val settings = ProviderContextSettings(manualWindow = 256_000)
        assertNull(
            ContextCapacity.failure(
                request.messages.size,
                request.inputTokens(),
                request.maxOutputTokens,
                settings.window,
                settings.window,
            ),
        )
        assertFalse(ContextCapacity.shouldCompact(request, settings, settings.window, request.inputTokens()))
    }
}
