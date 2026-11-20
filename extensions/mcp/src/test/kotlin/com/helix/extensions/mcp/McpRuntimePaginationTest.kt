package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.McpServerId
import com.helix.core.model.NoCancellation
import com.helix.core.model.NormalizedEndpoint
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Full production runtime and HTTP SDK, including its exact config/endpoint source identity. */
class McpRuntimePaginationTest {
    private val names = listOf("list_mcp_resources", "list_mcp_resource_templates")
    private val runtime =
        McpToolRuntime(
            McpCredentialLookup { error("keyless fixture") },
            McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) },
            "pagination-test",
            "1",
        )

    @Test
    fun bothListTypesFollowThreePagesWithOpaqueEmptyServerCursor() {
        ResourceFixture("opaque-empty").use { fixture ->
            val config = McpServerConfig.disabled("fixture", fixture.endpoint).copy(enabled = true)
            names.forEach { name ->
                val first = page(config, name, "")
                assertTrue(first.toString().contains("Page0"))
                val second = page(config, name, cursor(first))
                assertTrue(second.toString().contains("Page1"))
                val third = page(config, name, cursor(second))
                assertTrue(third.toString().contains("Page2"))
                assertTrue(third["nextCursor"] == null)
            }
            assertEquals(6, fixture.methods.count { it == "initialize" })
            assertEquals(4, fixture.cursors.count { it == "" })
            assertEquals(2, fixture.cursors.count { it.startsWith("opaque-") })
        }
    }

    @Test
    fun emptyPageWithContinuationAndWhitespaceServerCursorArePreserved() {
        names.forEach { name ->
            ResourceFixture("opaque-empty-space").use { fixture ->
                val config = McpServerConfig.disabled("fixture", fixture.endpoint).copy(enabled = true)
                val first = page(config, name, null)
                assertEquals("[]", first.getValue("entries").toString())
                val second = page(config, name, cursor(first))
                assertTrue(second.toString().contains("Page1"))
                val third = page(config, name, cursor(second))
                assertTrue(third.toString().contains("Page2"))
                assertTrue(fixture.cursors.contains("  "))
                assertTrue(third["nextCursor"] == null)
            }
        }
    }

    @Test
    fun sourceEndpointAndListTypeChangesRejectBeforeAnyConnection() {
        ResourceFixture("opaque-empty").use { fixture ->
            val config = McpServerConfig.disabled("fixture", fixture.endpoint).copy(enabled = true)
            names.forEach { name ->
                val continuation = cursor(page(config, name, null))
                val requests = fixture.methods.size
                val candidates =
                    listOf(
                        config.copy(id = McpServerId("other")) to name,
                        config.copy(endpoint = NormalizedEndpoint.parse(fixture.endpoint + "/changed")) to name,
                        config to names.single { it != name },
                    )
                candidates.forEach { (changed, operation) ->
                    val failure = assertThrows(McpCallException::class.java) { page(changed, operation, continuation) }
                    assertFalse(failure.requestSent)
                    assertEquals(0, failure.connectionAttempts)
                    assertEquals(
                        ResourceCursorFailure.SOURCE_CHANGED,
                        (failure.cause as ResourceCursorException).reason,
                    )
                }
                assertEquals(requests, fixture.methods.size)
            }
        }
    }

    @Test
    fun changedMetadataAndRepeatedOpaqueCursorsFailWithoutUnboundedReplay() {
        for (mode in listOf("opaque-empty", "opaque-empty-cycle")) {
            names.forEach { name -> verifyListingFailure(mode, name) }
        }
    }

    private fun verifyListingFailure(
        mode: String,
        name: String,
    ) {
        ResourceFixture(mode).use { fixture ->
            val config = McpServerConfig.disabled("fixture", fixture.endpoint).copy(enabled = true)
            val continuation = cursor(page(config, name, null))
            if (mode == "opaque-empty") fixture.changedFirstPage = true
            val failure = bridgeFailure(config, name, continuation)
            assertFalse(failure.sideEffectFree)
            assertFalse(failure.requiresReview)
            assertTrue(failure.detail.contains("request=REPLIED"))
            val reason = if (mode == "opaque-empty") "LISTING_CHANGED" else "CURSOR_CYCLE"
            assertTrue(failure.detail.contains(reason))
            assertEquals(2, fixture.methods.count { it == "initialize" })
            val protocolMethod = if (name == names.first()) "resources/list" else "resources/templates/list"
            assertEquals(if (mode == "opaque-empty") 2 else 3, fixture.methods.count { it == protocolMethod })
        }
    }

    @Test
    fun completedPageOverBudgetReportsIncompleteListingNotUnknown() {
        names.forEach { name ->
            ResourceFixture("opaque-empty-limit").use { fixture ->
                val config = McpServerConfig.disabled("fixture", fixture.endpoint).copy(enabled = true)
                val failure = bridgeFailure(config, name, null)
                assertFalse(failure.requiresReview)
                assertFalse(failure.sideEffectFree)
                assertTrue(failure.detail.contains("LIMIT_EXCEEDED"))
                assertTrue(failure.detail.contains("Listing is incomplete"))
                assertEquals(1, fixture.methods.count { it == "initialize" })
            }
        }
    }

    private fun bridgeFailure(
        config: McpServerConfig,
        name: String,
        cursor: String?,
    ): com.helix.tools.framework.ToolExecutorResult.Failed {
        val registry =
            com.helix.tools.framework
                .ToolRegistry()
        McpDynamicToolBridge(
            config,
            McpServerIdentity("fixture", "1", "2025-03-26"),
            emptyList(),
            McpToolCaller {
                _,
                _,
                ->
                error("not tools/call")
            },
            runtime.caller(config, resourceOperation = true),
        ).register(registry)
        val toolName =
            com.helix.tools.framework.RemoteToolNames
                .mcp(config.id.value, name)
        val descriptor = registry.all().single { it.name == toolName }
        val call =
            ExecutableToolCall(
                "call",
                toolName.value,
                "1",
                buildJsonObject { cursor?.let { put("cursor", it) } },
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                NoCancellation,
            )
        return registry
            .executor(
                descriptor.name,
                descriptor.version,
            ).execute(call) as com.helix.tools.framework.ToolExecutorResult.Failed
    }

    private fun page(
        config: McpServerConfig,
        name: String,
        cursor: String?,
    ): JsonObject {
        val call =
            ExecutableToolCall(
                "call",
                name,
                "1",
                buildJsonObject { cursor?.let { put("cursor", it) } },
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                NoCancellation,
            )
        return runtime
            .caller(config, resourceOperation = true)
            .call(call, name)
            .structuredContent!!
            .getValue("resource")
            .jsonObject
    }

    private fun cursor(page: JsonObject): String = page.getValue("nextCursor").jsonPrimitive.content
}
