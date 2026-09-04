package com.helix.app.mcp

import com.helix.core.model.McpServerId
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.repository.McpCapabilityKind
import com.helix.extensions.mcp.McpCapabilitySnapshot
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.McpMetadataSnapshot
import com.helix.extensions.mcp.McpPromptMetadata
import com.helix.extensions.mcp.McpResourceMetadata
import com.helix.extensions.mcp.McpServerIdentity
import com.helix.extensions.mcp.McpToolMetadata
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStorageBridgeTest {
    @Test
    fun handshakeMapsToCapabilityToolResourceAndPromptRows() {
        val snapshot = snapshot()

        val specs = snapshot.toCapabilitySpecs()

        assertEquals(4, specs.size)
        assertEquals(
            listOf(
                McpCapabilityKind.CAPABILITY,
                McpCapabilityKind.TOOL,
                McpCapabilityKind.RESOURCE,
                McpCapabilityKind.PROMPT,
            ),
            specs.map { it.kind },
        )
        assertTrue(specs.all { it.protocolVersion == "2025-03-26" })
        assertEquals("a".repeat(64), specs.single { it.kind == McpCapabilityKind.TOOL }.contentHash)
        assertEquals("b".repeat(64), specs.single { it.kind == McpCapabilityKind.RESOURCE }.contentHash)
        assertEquals("c".repeat(64), specs.single { it.kind == McpCapabilityKind.PROMPT }.contentHash)
    }

    private fun snapshot(): McpHandshakeSnapshot =
        McpHandshakeSnapshot(
            serverId = McpServerId("mcp-server-1"),
            endpoint = "https://mcp.example.com:443/mcp",
            origin = "https://mcp.example.com:443",
            residence = ProviderResidence.PUBLIC_CLOUD,
            identity = McpServerIdentity("fixture", "1", "2025-03-26"),
            metadata =
                McpMetadataSnapshot(
                    capabilities = McpCapabilitySnapshot(true, true, true, false, false, false, false),
                    tools =
                        listOf(
                            McpToolMetadata(
                                name = "read",
                                title = null,
                                description = null,
                                inputSchema = buildJsonObject { put("type", "object") },
                                outputSchema = null,
                                schemaHash = "a".repeat(64),
                                serverProvidedHints = emptyMap(),
                            ),
                        ),
                    resources =
                        listOf(
                            McpResourceMetadata("fixture://docs", "docs", null, null, null, null, "b".repeat(64)),
                        ),
                    prompts =
                        listOf(
                            McpPromptMetadata("review", null, null, emptyList(), false, "c".repeat(64)),
                        ),
                    toolsHaveMore = false,
                    resourcesHaveMore = false,
                    promptsHaveMore = false,
                ),
        )
}
