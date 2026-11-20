package com.helix.app.mcp

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDiscoveryHints
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ToolDiscoveryHintsTest {
    private val registry = ToolRegistry()
    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools_search")))
    private val neverExecute =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult = error("Discovery cannot execute")
        }

    @Test fun explicitHintsAndNestedParameterDescriptionsAreSearchableGlobally() {
        val tool =
            search.copy(
                name = ToolName("sample_lookup"),
                description = "Look up an item",
                discovery = ToolDiscoveryHints("files", setOf("inventory", "库存")),
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"options":{"type":"object","properties":{
                "needle":{"type":"string","description":"Invoice reference supplied by the vendor"}
                }}}}""",
                        ).jsonObject,
            )
        registry.register(tool, neverExecute)
        for (query in listOf("invoice", "vendor", "needle", "inventory", "库存")) {
            assertEquals(listOf(tool), discovery.search("s", query, 8))
        }
        assertEquals(listOf(tool), discovery.search("s", "inventory", 8, category = "files"))
        assertTrue(discovery.search("s", "inventory", 8, category = "browser").isEmpty())
        val denied = McpToolDiscovery(registry) { _, descriptor -> descriptor != tool }
        assertTrue(denied.search("s", "invoice", 8).isEmpty())
    }

    @Test fun schemaExampleValuesCannotCreateSearchTerms() {
        val tool =
            search.copy(
                name = ToolName("sample_lookup"),
                description = "Look up an item",
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"options":{"type":"object",
                "default":{"description":"not-a-search-hint"},
                "examples":[{"description":"not-a-search-hint"}]}}}""",
                        ).jsonObject,
            )
        registry.register(tool, neverExecute)
        assertTrue(discovery.search("s", "not-a-search-hint", 8).isEmpty())
    }

    @Test fun filteredMissIsExplicitAndDoesNotLoadUnfilteredTools() {
        val tool =
            search.copy(
                name = ToolName("sample_lookup"),
                description = "Inventory lookup",
                discovery = ToolDiscoveryHints("files"),
            )
        registry.register(tool, neverExecute)
        discovery.visible("s", registry.all())
        val output = execute("""{"query":"inventory","category":"browser"}""")
        assertEquals("no_matches", output["status"]?.jsonPrimitive?.content)
        assertTrue(
            output
                .getValue("retryHint")
                .jsonPrimitive.content
                .contains("without source/category"),
        )
        assertFalse(discovery.visible("s", registry.all()).contains(tool))
        val retry = execute("""{"query":"inventory"}""")
        assertEquals("matched", retry["status"]?.jsonPrimitive?.content)
        assertFalse("retryHint" in retry)
        assertTrue(discovery.visible("s", registry.all()).contains(tool))
    }

    @Test fun catalogRevisionIncludesHintsRatherThanOnlyToolVersion() {
        fun revision(tool: com.helix.tools.framework.ToolDescriptor): String {
            val catalog = ToolRegistry().also { it.register(tool, neverExecute) }
            val finder = McpToolDiscovery(catalog)
            finder.visible("s", catalog.all())
            return requireNotNull(finder.catalog("s")).entries.single().revision
        }
        val tool = search.copy(name = ToolName("sample_lookup"), discovery = ToolDiscoveryHints("files"))
        val before = revision(tool)
        val after = revision(tool.copy(discovery = ToolDiscoveryHints("files", setOf("invoices"))))
        assertFalse(before == after)
    }

    @Test fun globalMissHasAnActionableHintAndValidSchema() {
        val output = execute("""{"query":"no-such-capability"}""")
        assertEquals("no_matches", output["status"]?.jsonPrimitive?.content)
        assertTrue(
            output
                .getValue("retryHint")
                .jsonPrimitive.content
                .contains("shorter purpose"),
        )
    }

    private fun execute(args: String): kotlinx.serialization.json.JsonObject {
        val call =
            ExecutableToolCall(
                "search",
                search.name.value,
                search.version.value.toString(),
                Json.parseToJsonElement(args).jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                object : CancelSignal {
                    override fun isCancelled() = false
                },
                "s",
            )
        val result = registry.executor(search.name, search.version).execute(call) as ToolExecutorResult.Completed
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(search.outputSchema, result.output))
        return result.output.jsonObject
    }
}
