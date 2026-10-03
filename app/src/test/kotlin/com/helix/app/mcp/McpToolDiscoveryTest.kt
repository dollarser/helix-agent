package com.helix.app.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelRequest
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutorResult
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

    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools.search")))

    private val fixtureExecutor =
        object : com.helix.tools.framework.ToolExecutor {
            override fun execute(call: ExecutableToolCall) =
                ToolExecutorResult.Completed(kotlinx.serialization.json.buildJsonObject {})
        }

    private fun registerFixture(descriptor: ToolDescriptor) = registry.register(descriptor, fixtureExecutor)

    private fun replaceCatalog(
        server: String,
        descriptors: List<ToolDescriptor>,
    ) = registry.replaceMcpServer(
        server,
        descriptors.map {
            com.helix.tools.framework
                .ToolBinding(it, fixtureExecutor)
        },
    )

    private fun remote(index: Int): ToolDescriptor =
        search.copy(
            name = ToolName("mcp.catalog.tool_$index"),
            description = "catalog operation $index",
            operationClass = ToolOperationClass.NETWORK,
            origin = ToolOrigin.McpOrigin("catalog", "2025-03-26", "a".repeat(64)),
        )

    private fun catalog(count: Int = 500) = replaceCatalog("catalog", (0 until count).map(::remote))

    @Test fun optionalBuiltInNeedsDiscoveryAndNeverLeaksAcrossSessions() {
        val time = search.copy(name = ToolName("time.now"), description = "Current time")
        registerFixture(time)
        val defaults =
            com.helix.app.chat.ModelToolExposureOrder
                .defaultNames(preferUi = false)
        assertFalse(time in discovery.visible("session", registry.all(), defaults))
        assertEquals(listOf(time), discovery.search("session", "time.now", 1))
        assertTrue(time in discovery.visible("session", registry.all(), defaults))
        assertFalse(time in discovery.visible("another-session", registry.all(), defaults))
    }

    @Test fun searchChecksAvailabilityOnlyForMatchingCandidatesUntilWindowIsFull() {
        catalog()
        val checked = mutableListOf<String>()
        val filtered =
            McpToolDiscovery(registry) { _, descriptor ->
                checked += descriptor.name.value
                descriptor.name.value != "mcp.catalog.tool_0"
            }
        assertEquals(listOf(remote(499)), filtered.search("session", "tool_499", 1))
        assertEquals(listOf("mcp.catalog.tool_499"), checked)
        checked.clear()
        assertEquals(listOf(remote(1)), filtered.search("session", "catalog", 1))
        assertEquals(listOf("mcp.catalog.tool_0", "mcp.catalog.tool_1"), checked)
    }

    @Test fun exactNameAndNameMatchesRankAheadOfDescriptionMentions() {
        val exact = search.copy(name = ToolName("files.archive"), description = "Create an archive")
        val mention = search.copy(name = ToolName("aaa.helper"), description = "Use files.archive to archive files")
        registerFixture(exact)
        registerFixture(mention)
        assertEquals(listOf(exact), discovery.search("session", "files.archive", 1))
        assertEquals(exact, discovery.search("session", "archive", 2).first())
    }

    @Test fun compositeQueryFallsBackToRankedPartialMatches() {
        val click = search.copy(name = ToolName("android.ui.click"), description = "Click Android UI controls")
        val capture = search.copy(name = ToolName("android.screen.capture"), description = "Capture the Android screen")
        val weak = search.copy(name = ToolName("misc.screen.helper"), description = "Screen helper")
        registerFixture(click)
        registerFixture(capture)
        registerFixture(weak)

        val found = discovery.search("session", "android ui click screen", 3)

        assertEquals(listOf(click, capture, weak), found)
    }

    @Test fun completeTermMatchesStillRankAheadOfOrFallbacks() {
        val complete = search.copy(name = ToolName("android.ui.click"), description = "Click a button on Android UI")
        val partial = search.copy(name = ToolName("android.screen"), description = "Inspect Android UI")
        registerFixture(complete)
        registerFixture(partial)

        val found = discovery.search("session", "android ui click", 2)

        assertEquals(listOf(complete, partial), found)
    }

    @Test fun missCannotRetainReplacedOrDisabledBindings() {
        catalog()
        var enabled = true
        val filtered = McpToolDiscovery(registry) { _, _ -> enabled }
        filtered.search("session", "tool_499", 1)
        enabled = false
        assertTrue(filtered.search("session", "absent", 1).isEmpty())
        enabled = true
        assertEquals(listOf(search), filtered.visible("session", registry.all()))
        filtered.search("session", "tool_499", 1)
        replaceCatalog("catalog", listOf(remote(499).copy(description = "replacement")))
        filtered.search("session", "absent", 1)
        // Add a large directory to avoid the independent small-catalog automatic exposure.
        replaceCatalog("catalog", (0 until 500).map { remote(it).copy(description = "replacement") })
        assertEquals(listOf(search), filtered.visible("session", registry.all()))
    }

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
    fun discoveryAndLoadedWindowSurviveTheModelToolLimit() {
        catalog()
        val other = (0 until ModelRequest.MAX_TOOLS).map { search.copy(name = ToolName("local.tool_$it")) }
        val admitted = other + registry.all()
        assertTrue(search in discovery.visible("session", admitted).take(ModelRequest.MAX_TOOLS))
        val found = discovery.search("session", "catalog", McpToolDiscovery.WINDOW)
        val exposed = discovery.visible("session", admitted).take(ModelRequest.MAX_TOOLS)
        assertTrue(search in exposed)
        assertTrue(exposed.containsAll(found))
        assertEquals(exposed.size, exposed.distinctBy { it.name }.size)
    }

    @Test
    fun searchedSmallCatalogToolAlsoSurvivesTheModelToolLimit() {
        catalog(3)
        val other = (0 until ModelRequest.MAX_TOOLS).map { search.copy(name = ToolName("local.tool_$it")) }
        val found = discovery.search("session", "tool_2", 1).single()
        assertTrue(found in discovery.visible("session", other + registry.all()).take(ModelRequest.MAX_TOOLS))
        assertFalse(found in discovery.visible("session", other + listOf(search)))
    }

    @Test
    fun newSearchReplacesWindowAndNeverGrowsBeyondSixteen() {
        catalog()
        assertEquals(16, discovery.search("session", "catalog", 16).size)
        val selected = discovery.search("session", "tool_499", 1)
        assertEquals(2, discovery.visible("session", registry.all()).size)
        assertTrue(discovery.search("session", "no match", 8).isEmpty())
        assertEquals(listOf(search) + selected, discovery.visible("session", registry.all()))
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
        replaceCatalog("catalog", old.map { if (it == selected) changed else it })
        assertFalse(selected.contractHash == changed.contractHash)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertEquals(listOf(changed), discovery.search("session", "tool_499", 1))
        replaceCatalog("catalog", emptyList())
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
        assertEquals(ToolExecutorResult.Cancelled, registry.executor(search.name, ToolVersion(1)).execute(call))
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }

    @Test fun optionalBuiltInIsSearchableAndOnlyLoadedForItsSession() {
        val local = search.copy(name = ToolName("files.archive"), description = "Create a file archive")
        registerFixture(local)
        assertFalse(local in discovery.visible("session", registry.all()))
        assertEquals(listOf(local), discovery.search("session", "archive", 8))
        assertTrue(local in discovery.visible("session", registry.all()))
        assertFalse(local in discovery.visible("other", registry.all()))
        assertFalse(local in discovery.visible("session", listOf(search)))
        assertFalse(local in McpToolDiscovery(registry).visible("session", registry.all()))
    }

    @Test fun builtInDisableAppliesToSearchAndLoadedSchemas() {
        val local = search.copy(name = ToolName("files.archive"), description = "Create a file archive")
        registerFixture(local)
        var enabled = true
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor != local || enabled }
        assertEquals(listOf(local), filtered.search("session", "archive", 8))
        enabled = false
        assertTrue(filtered.search("session", "archive", 8).isEmpty())
        assertFalse(local in filtered.visible("session", registry.all(), setOf(local.name.value)))
    }

    @Test fun defaultsAndDiscoveryShareOneBoundedSurface() {
        val locals = (0 until 100).map { search.copy(name = ToolName("local.tool_$it")) }
        locals.forEach(::registerFixture)
        val defaults = setOf("local.tool_0", "local.tool_1")
        assertEquals(3, discovery.visible("session", registry.all(), defaults).size)
        discovery.search("session", "local.tool_99", 1)
        assertEquals(4, discovery.visible("session", registry.all(), defaults).size)
        assertEquals(3, discovery.visible("other", registry.all(), defaults).size)
    }

    @Test fun currentModeAdmissionAlsoBoundsSearchResults() {
        val local = search.copy(name = ToolName("files.archive"), description = "Create a file archive")
        registerFixture(local)
        discovery.visible("session", listOf(search))
        assertTrue(discovery.search("session", "archive", 8).isEmpty())
        discovery.visible("session", listOf(search, local))
        assertEquals(listOf(local), discovery.search("session", "archive", 8))
        discovery.visible("session", listOf(search))
        assertFalse(local in discovery.visible("session", listOf(search)))
    }

    // HXA-209 B3: the shared availability predicate (the same lambda the execution entry uses)
    // also gates discovery — a disabled remote tool is neither searchable nor visible, and a
    // disable that lands after a search removes it from the loaded window.
    @Test
    fun aDisabledToolLeavesSearchAndTheVisibleWindow() {
        catalog(3)
        val disabled = mutableSetOf("mcp.catalog.tool_1")
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor.name.value !in disabled }
        assertEquals(
            listOf("mcp.catalog.tool_0", "mcp.catalog.tool_2"),
            filtered.search("session", "catalog", 8).map { it.name.value },
        )
        val visible = filtered.visible("session", registry.all()).map { it.name.value }
        assertFalse(visible.contains("mcp.catalog.tool_1"))
        assertTrue(visible.contains("tools.search"))
        assertTrue(visible.containsAll(listOf("mcp.catalog.tool_0", "mcp.catalog.tool_2")))
    }

    @Test
    fun aDisableLandedAfterSearchRemovesItFromTheWindow() {
        catalog(3)
        val disabled = mutableSetOf<String>()
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor.name.value !in disabled }
        assertEquals(listOf("mcp.catalog.tool_1"), filtered.search("session", "tool_1", 1).map { it.name.value })
        disabled += "mcp.catalog.tool_1"
        val visible = filtered.visible("session", registry.all()).map { it.name.value }
        assertFalse(
            "a disable landing after the search removes the loaded window",
            visible.contains("mcp.catalog.tool_1"),
        )
        assertTrue(visible.contains("tools.search"))
        assertTrue(filtered.search("session", "tool_1", 1).isEmpty())
    }
}
