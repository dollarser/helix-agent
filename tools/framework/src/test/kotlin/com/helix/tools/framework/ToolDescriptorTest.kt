package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.TestFixtures.builtIn
import com.helix.tools.framework.TestFixtures.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ToolDescriptorTest {
    /** A descriptor built with an MCP origin (bypassing the source, to test the contract itself). */
    private fun mcpDescriptor(
        name: String,
        operationClass: ToolOperationClass,
        serverId: String = "srv",
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(1),
            description = "test mcp tool",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            operationClass = operationClass,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 1024 * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.McpOrigin(serverId, 1),
        )

    @Test
    fun validDescriptorExposesItsSchemaHash() {
        val d = builtIn()
        assertEquals(64, d.schemaHash.hex.length)
        // identical contracts hash identically; a schema change changes the hash
        val same = builtIn()
        assertEquals(d.schemaHash, same.schemaHash)
        val changed = d.copy(inputSchema = json("""{"type":"object","required":["x"]}"""))
        assertTrue(d.schemaHash != changed.schemaHash)
    }

    @Test
    fun blankOrOverlongDescriptionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { builtIn().copy(description = "  ") }
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(description = "x".repeat(ToolDescriptor.MAX_DESCRIPTION_LENGTH + 1))
        }
        // the boundary length is accepted
        builtIn().copy(description = "x".repeat(ToolDescriptor.MAX_DESCRIPTION_LENGTH))
    }

    @Test
    fun timeoutMustBePositiveAndWithinTheFrameworkCap() {
        assertThrows(IllegalArgumentException::class.java) { builtIn(timeoutSeconds = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(timeout = ToolDescriptor.MAX_TIMEOUT + 1.seconds)
        }
        // the boundary value is accepted
        builtIn().copy(timeout = ToolDescriptor.MAX_TIMEOUT)
        assertThrows(IllegalArgumentException::class.java) { builtIn().copy(timeout = 30.days) }
    }

    @Test
    fun maxOutputBytesMustBePositiveAndWithinTheFrameworkCap() {
        assertThrows(IllegalArgumentException::class.java) { builtIn(maxOutputBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(maxOutputBytes = ToolDescriptor.MAX_OUTPUT_BYTES_CAP + 1)
        }
        // the boundary value is accepted
        builtIn().copy(maxOutputBytes = ToolDescriptor.MAX_OUTPUT_BYTES_CAP)
    }

    @Test
    fun originAndNameNamespaceMustAgree() {
        // built-in name with an MCP origin
        assertThrows(IllegalArgumentException::class.java) {
            builtIn(name = "read").copy(origin = ToolOrigin.McpOrigin("srv", 1))
        }
        // an mcp.* name with a built-in origin
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(name = ToolName("mcp.srv.tool"))
        }
    }

    @Test
    fun mcpToolsCanNeverBeReadOnlyEvenWhenHelixDeclaresIt() {
        assertThrows(IllegalArgumentException::class.java) {
            mcpDescriptor("mcp.srv.tool", ToolOperationClass.READ_ONLY)
        }
        // a non-READ_ONLY MCP classification is accepted (effect at least NETWORK)
        val d = mcpDescriptor("mcp.srv.tool", ToolOperationClass.NETWORK)
        assertEquals(ToolOperationClass.NETWORK, d.operationClass)
    }

    @Test
    fun mcpOriginBindsServerFactsAndRejectsBadProtocolVersion() {
        val origin = ToolOrigin.McpOrigin("srv", 1, mapOf("readOnlyHint" to true))
        assertEquals("srv", origin.serverId)
        assertEquals(1, origin.protocolVersion)
        assertEquals(mapOf("readOnlyHint" to true), origin.serverProvidedHints)
        assertThrows(IllegalArgumentException::class.java) { ToolOrigin.McpOrigin("srv", 0) }
    }

    @Test
    fun executionTargetIsPartOfTheContract() {
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, builtIn().executionTarget)
    }

    @Test
    fun outOfSubsetInputSchemaIsRejectedAtConstruction() {
        // "unknown keyword 拒绝注册": the descriptor (and therefore the
        // registration) cannot be built with an out-of-subset schema.
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(inputSchema = json("""{"type":"string","format":"uri"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(inputSchema = json("""{"anyOf":[{"type":"string"}]}"""))
        }
        // an out-of-type keyword is also rejected
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(inputSchema = json("""{"type":"object","minLength":1}"""))
        }
    }

    @Test
    fun outOfSubsetOutputSchemaIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(outputSchema = json("""{"type":"object","const":1}"""))
        }
        // a catastrophic pattern in the output schema is rejected too
        assertThrows(IllegalArgumentException::class.java) {
            builtIn().copy(outputSchema = json("""{"type":"string","pattern":"(a+)+"}"""))
        }
    }

    @Test
    fun validSubsetSchemasAreAccepted() {
        val d =
            builtIn().copy(
                inputSchema =
                    json(
                        """
                        {"type":"object","required":["path"],
                         "properties":{"path":{"type":"string","minLength":1,"pattern":"^/"}},
                         "additionalProperties":false}
                        """,
                    ),
            )
        assertEquals("read", d.name.value)
    }
}
