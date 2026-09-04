package com.helix.extensions.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpStdioProtocolTest {
    @Test
    fun commandIsCopiedBoundedAndFingerprintStable() {
        val mutable = mutableListOf("/usr/bin/mcp-server", "--stdio")
        val locked = McpStdioServerCommand.locked(mutable)
        mutable[0] = "attacker"
        assertEquals("/usr/bin/mcp-server", locked.arguments.first())
        assertEquals(locked.fingerprintSha256, McpStdioServerCommand.locked(locked.arguments).fingerprintSha256)
        assertNotEquals(locked.fingerprintSha256, McpStdioServerCommand.locked(listOf("attacker")).fingerprintSha256)
        assertThrows(IllegalArgumentException::class.java) { McpStdioServerCommand.locked(listOf("bad\narg")) }
    }

    @Test
    fun clientTranscriptIsNewlineDelimitedAndBounded() {
        val request =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
            }
        val notification =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
        assertEquals(
            "$request\n$notification\n",
            McpStdioProtocol.encodeClientMessages(listOf(request, notification)).decodeToString(),
        )
    }

    @Test
    fun responsesAndNotificationsAreAcceptedButReverseRequestsAreRejected() {
        val stdout =
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n" +
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n"
            ).encodeToByteArray()
        val output = McpStdioProtocol.parseServerOutput(stdout, "diagnostic".encodeToByteArray())
        assertEquals(2, output.messages.size)
        assertEquals("diagnostic", output.stderr)

        assertFailure(
            McpStdioFailure.REVERSE_REQUEST,
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\"}\n".encodeToByteArray(),
        )
    }

    @Test
    fun stdoutMustContainOnlyCompleteStrictJsonRpcFrames() {
        assertFailure(McpStdioFailure.UNTERMINATED_FRAME, "log noise".encodeToByteArray())
        assertFailure(McpStdioFailure.EMPTY_FRAME, "\n".encodeToByteArray())
        assertFailure(McpStdioFailure.INVALID_JSON_RPC, "server started\n".encodeToByteArray())
        assertFailure(McpStdioFailure.INVALID_JSON_RPC, "{}\n".encodeToByteArray())
        assertFailure(
            McpStdioFailure.INVALID_JSON_RPC,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}\n".encodeToByteArray(),
        )
        assertFailure(McpStdioFailure.INVALID_UTF8, byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
    }

    @Test
    fun stdoutAndStderrHaveIndependentLimits() {
        val limits = McpStdioLimits(maxLineBytes = 256, maxStdoutBytes = 256, maxStderrBytes = 1024)
        assertFailure(McpStdioFailure.STDOUT_LIMIT, ByteArray(257), ByteArray(0), limits)
        assertFailure(McpStdioFailure.STDERR_LIMIT, ByteArray(0), ByteArray(1025), limits)
    }

    private fun assertFailure(
        expected: McpStdioFailure,
        stdout: ByteArray,
        stderr: ByteArray = ByteArray(0),
        limits: McpStdioLimits = McpStdioLimits(),
    ) {
        val thrown =
            assertThrows(McpStdioProtocolException::class.java) {
                McpStdioProtocol.parseServerOutput(stdout, stderr, limits)
            }
        assertEquals(expected, thrown.failure)
    }
}
