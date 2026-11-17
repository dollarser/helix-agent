package com.helix.app.eval

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.extensions.mcp.McpClientFacade
import com.helix.extensions.mcp.McpClientSession
import com.helix.extensions.mcp.McpCredentialLookup
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpMetadataLimits
import com.helix.extensions.mcp.McpMetadataSnapshot
import com.helix.extensions.mcp.McpNetworkPermit
import com.helix.extensions.mcp.McpResourceContent
import com.helix.extensions.mcp.McpResourcePage
import com.helix.extensions.mcp.McpResultBlock
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpServerIdentity
import com.helix.extensions.mcp.McpToolMetadata
import com.helix.extensions.mcp.McpToolResult
import com.helix.extensions.mcp.McpToolResultLimits
import com.helix.extensions.mcp.McpToolRuntime
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Injected transport only: no sockets, stored account, network scope or permission changes. */
class McpRuntimeBoundaryDeviceTest {
    private val config = McpServerConfig.disabled("device-fixture", "https://fixture.invalid/mcp").copy(enabled = true)
    private val session = Session()
    private var connections = 0
    private val runtime =
        McpToolRuntime(
            McpCredentialLookup { error("No credentials") },
            McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) },
            "device-fixture",
            "1",
            clientFactory = { _, _, _ ->
                object : McpClientFacade {
                    override suspend fun connect(endpointUrl: String): McpClientSession {
                        connections++
                        return session
                    }
                }
            },
        )

    @Test fun bothListTypesTraverseThreePagesThroughRuntimeWithFreshConnections() {
        for (name in listOf("list_mcp_resources", "list_mcp_resource_templates")) {
            var cursor: String? = null
            repeat(3) { index ->
                val page =
                    runtime
                        .caller(config, resourceOperation = true)
                        .call(call(name, buildJsonObject { cursor?.let { put("cursor", it) } }), name)
                        .structuredContent!!
                        .getValue("resource")
                        .jsonObject
                assertTrue(page.getValue("entries").toString().contains("Page$index"))
                cursor = page["nextCursor"]?.jsonPrimitive?.content
            }
            assertEquals(null, cursor)
        }
        assertEquals(6, connections)
        assertEquals(6, session.closed)
        assertEquals(4, session.cursors.count { it == "" })
        assertEquals(2, session.cursors.count { it == "  " })
    }

    @Test fun receivedToolErrorKeepsFullBlocksAndStructuredOutputWithoutReplay() {
        val registry = ToolRegistry()
        val metadata =
            McpToolMetadata(
                "search",
                null,
                "Fixture",
                buildJsonObject { put("type", "object") },
                null,
                "a".repeat(64),
                emptyMap(),
            )
        McpDynamicToolBridge(config, session.server, listOf(metadata), runtime.caller(config)).register(registry)
        val descriptor = registry.all().single()
        val result =
            registry
                .executor(descriptor.name, descriptor.version)
                .execute(call(descriptor.name.value, buildJsonObject {})) as ToolExecutorResult.FailedWithOutput
        val output = result.output.jsonObject
        assertEquals("true", output.getValue("isError").jsonPrimitive.content)
        assertTrue(output.getValue("blocks").toString().contains("Correct the query"))
        assertEquals(
            "bad_query",
            output
                .getValue("structuredContent")
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content,
        )
        assertEquals(1, session.toolCalls)
        assertEquals(1, connections)
        assertEquals(1, session.closed)
    }

    private fun call(
        name: String,
        args: JsonObject,
    ) = ExecutableToolCall(
        "device-call",
        name,
        "1",
        args,
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(5),
        NoCancellation,
    )

    private class Session : McpClientSession {
        override val server = McpServerIdentity("device-fixture", "1", "2025-03-26")
        val cursors = mutableListOf<String?>()
        var closed = 0
        var toolCalls = 0

        override suspend fun close() {
            closed++
        }

        override suspend fun ping() = Unit

        override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("Unused")

        override suspend fun readResource(uri: String): List<McpResourceContent> = error("Unused")

        override suspend fun listResourceTemplates(cursor: String?) = listResources(cursor)

        override suspend fun listResources(cursor: String?): McpResourcePage {
            cursors += cursor
            val index =
                when (cursor) {
                    null -> 0
                    "" -> 1
                    "  " -> 2
                    else -> error("Unexpected cursor")
                }
            return McpResourcePage(
                listOf(
                    buildJsonObject {
                        put("name", "Page$index")
                        put("uri", "memo://$index")
                    },
                ),
                when (index) {
                    0 -> ""
                    1 -> "  "
                    else -> null
                },
            )
        }

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
            limits: McpToolResultLimits,
        ): McpToolResult {
            toolCalls++
            return McpToolResult(
                true,
                listOf(McpResultBlock.Text("Correct the query")),
                buildJsonObject {
                    put("code", "bad_query")
                },
            )
        }
    }
}
