package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.policy.DataSensitivity
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class McpDynamicToolBridgeTest {
    @Test
    fun registersNamespacedToolAndNeverTrustsReadOnlyOrIdempotentHints() {
        val bridge = bridge(metadata(hints = mapOf("readOnlyHint" to true, "idempotentHint" to true)))
        val descriptor = bridge.descriptors().single()

        assertEquals("mcp.fixture.search", descriptor.name.value)
        assertEquals(com.helix.core.model.ToolOperationClass.NETWORK, descriptor.operationClass)
        assertEquals(com.helix.tools.framework.Idempotency.NON_IDEMPOTENT, descriptor.idempotency)
        val origin = descriptor.origin as ToolOrigin.McpOrigin
        assertEquals("2025-03-26", origin.protocolVersion)
        assertEquals("a".repeat(64), origin.sourceSchemaHash)
        assertEquals(true, origin.serverProvidedHints["readOnlyHint"])
    }

    @Test
    fun schemaChangeReplacesDynamicContractAndInvalidatesItsHash() {
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        val first = bridge(metadata(schemaHash = "a".repeat(64)))
        first.register(registry, implementations)
        val firstContract = registry.all().single().contractHash

        val second = bridge(metadata(schemaHash = "b".repeat(64)))
        second.register(registry, implementations)

        assertEquals(1, registry.all().size)
        assertNotEquals(firstContract, registry.all().single().contractHash)
    }

    @Test
    fun maliciousOutOfSubsetServerSchemaIsRejectedBeforeRegistration() {
        val malicious =
            metadata().copy(
                inputSchema =
                    buildJsonObject {
                        put("type", "string")
                        put("format", "password")
                    },
            )

        assertThrows(IllegalArgumentException::class.java) { bridge(malicious) }
    }

    @Test
    fun resultBlocksFlowThroughTheRegisteredImplementationAsBoundedJson() {
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        val bridge =
            bridge(metadata()) {
                McpToolResult(
                    isError = false,
                    blocks = listOf(McpResultBlock.Text("answer")),
                    structuredContent = buildJsonObject { put("count", 1) },
                )
            }
        bridge.register(registry, implementations)
        val descriptor = registry.all().single()

        val result =
            implementations.resolve(descriptor.name, descriptor.version).execute(
                ExecutableToolCall(
                    toolCallId = "call-1",
                    toolName = descriptor.name.value,
                    toolVersion = descriptor.version.value.toString(),
                    args = buildJsonObject { put("query", "helix") },
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    deadline = Instant.now().plusSeconds(10),
                    cancel = NoCancellation,
                ),
            )

        assertTrue(result is ToolExecutorResult.Completed)
        val output = (result as ToolExecutorResult.Completed).output as JsonObject
        assertEquals(
            "answer",
            output["blocks"]
                ?.let {
                    it.toString()
                }.orEmpty()
                .substringAfter("text\":\"")
                .substringBefore('"'),
        )
    }

    @Test
    fun sendSummaryContainsOnlyBoundsAndChangesForceCheckpoints() {
        val bridge = bridge(metadata())
        val descriptor = bridge.descriptors().single()
        val tracker = McpSessionCheckpointTracker()
        val args = buildJsonObject { put("secret", "value") }

        val first = bridge.dispatchFacts(descriptor, args, DataSensitivity.NORMAL, tracker)
        val unchanged = bridge.dispatchFacts(descriptor, args, DataSensitivity.NORMAL, tracker)
        val changedCategory = bridge.dispatchFacts(descriptor, args, DataSensitivity.SENSITIVE, tracker)

        assertTrue(first.checkpointRequired)
        assertFalse(unchanged.checkpointRequired)
        assertTrue(changedCategory.checkpointRequired)
        assertEquals("https://mcp.example.com:443", first.sendSummary.origin)
        assertEquals(64, first.sendSummary.argumentHash.length)
        assertFalse(first.sendSummary.toString().contains("value"))
        assertEquals(
            McpServerId("fixture"),
            first.egress.target.let { (it as com.helix.core.policy.EgressTarget.Mcp).id },
        )
    }

    private fun bridge(
        tool: McpToolMetadata,
        result: (ExecutableToolCall) -> McpToolResult = {
            McpToolResult(false, listOf(McpResultBlock.Text("ok")), null)
        },
    ): McpDynamicToolBridge =
        McpDynamicToolBridge(
            config =
                McpServerConfig(
                    id = McpServerId("fixture"),
                    endpoint = NormalizedEndpoint.parse("https://mcp.example.com/mcp"),
                    enabled = true,
                ),
            identity = McpServerIdentity("fixture", "1", "2025-03-26"),
            metadata = listOf(tool),
            caller = McpToolCaller { call, _ -> result(call) },
        )

    private fun metadata(
        schemaHash: String = "a".repeat(64),
        hints: Map<String, Boolean> = emptyMap(),
    ): McpToolMetadata =
        McpToolMetadata(
            name = "search",
            title = null,
            description = "Search fixture",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject { put("query", buildJsonObject { put("type", "string") }) },
                    )
                },
            outputSchema = null,
            schemaHash = schemaHash,
            serverProvidedHints = hints,
        )
}
