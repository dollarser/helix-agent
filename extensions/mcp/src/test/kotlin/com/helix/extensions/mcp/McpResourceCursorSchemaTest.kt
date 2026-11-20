package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class McpResourceCursorSchemaTest {
    @Test
    fun schemaAdmitsBothFirstPageRepresentations() {
        McpResourceTools.metadata().filter { it.name != "read_mcp_resource" }.forEach { metadata ->
            val empty = buildJsonObject { put("cursor", "") }
            assertTrue(ToolSchemaValidator.validate(metadata.inputSchema, empty) is ToolSchemaValidation.Valid)
            assertTrue(
                ToolSchemaValidator.validate(metadata.inputSchema, buildJsonObject {}) is ToolSchemaValidation.Valid,
            )
        }
    }

    @Test
    fun malformedNonEmptyContinuationFailsBeforeNetworkOrSendMarker() {
        val runtime =
            McpToolRuntime(
                McpCredentialLookup { error("must not access credentials") },
                McpEndpointGate { error("must not access network") },
                "cursor-test",
                "1",
            )
        val config = McpServerConfig.disabled("fixture", "https://example.com/mcp").copy(enabled = true)
        for (name in listOf("list_mcp_resources", "list_mcp_resource_templates")) {
            for (cursor in listOf(" ", "invalid", "1:1:hash:wrong-source")) {
                val call =
                    ExecutableToolCall(
                        "call",
                        name,
                        "1",
                        buildJsonObject { put("cursor", cursor) },
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(10),
                        NoCancellation,
                    )
                val failure =
                    assertThrows(McpCallException::class.java) {
                        runtime
                            .caller(config, resourceOperation = true, onSend = { error("must not mark sent") })
                            .call(call, name)
                    }
                assertFalse(failure.requestSent)
                assertEquals(0, failure.connectionAttempts)
                assertTrue(failure.cause is IllegalArgumentException)
            }
        }
    }
}
