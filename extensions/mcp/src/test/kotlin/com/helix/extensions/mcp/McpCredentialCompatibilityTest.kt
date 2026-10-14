package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class McpCredentialCompatibilityTest {
    @Test
    fun colonSeparatedOpaqueCredentialReachesToolRuntimeUnchanged() {
        McpFixture().use { fixture ->
            val token = "synthetic-client:synthetic-access-token"
            val runtime =
                McpToolRuntime(
                    credentials = McpCredentialLookup { token },
                    endpointGate =
                        McpEndpointGate { endpoint ->
                            McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
                        },
                    clientName = "helix-test",
                    clientVersion = "1",
                )
            val caller =
                runtime.caller(
                    McpServerConfig
                        .disabled(
                            "opaque-credential",
                            fixture.endpoint,
                            "fixture.oauth.token",
                        ).copy(enabled = true),
                )
            val result =
                caller.call(
                    ExecutableToolCall(
                        toolCallId = "opaque-credential-call",
                        toolName = "echo",
                        toolVersion = "1",
                        args = buildJsonObject {},
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        deadline = Instant.now().plusSeconds(10),
                        cancel = NoCancellation,
                    ),
                    "echo",
                )
            assertFalse(result.isError)
            assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), fixture.methods)
            assertTrue(fixture.authorizations.all { it == "Bearer $token" })
            assertFalse(result.toString().contains(token))
        }
    }

    @Test
    fun visibleAsciiAndMaximumLengthAreAccepted() {
        requireBearerCredential(('!'..'~').joinToString(""))
        requireBearerCredential("x".repeat(4096))
    }

    @Test
    fun unsafeCredentialNeverReachesRuntimeNetworkOrSendCallback() {
        McpFixture().use { fixture ->
            var sent = false
            val caller =
                McpToolRuntime(
                    credentials = McpCredentialLookup { "bad\r\nAuthorization: injected" },
                    endpointGate =
                        McpEndpointGate { endpoint ->
                            McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
                        },
                    clientName = "helix-test",
                    clientVersion = "1",
                ).caller(
                    McpServerConfig
                        .disabled(
                            "invalid-credential",
                            fixture.endpoint,
                            "fixture.oauth.token",
                        ).copy(enabled = true),
                    onSend = { sent = true },
                )
            assertThrows(McpCredentialFormatException::class.java) {
                caller.call(
                    ExecutableToolCall(
                        toolCallId = "invalid-credential-call",
                        toolName = "echo",
                        toolVersion = "1",
                        args = buildJsonObject {},
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        deadline = Instant.now().plusSeconds(10),
                        cancel = NoCancellation,
                    ),
                    "echo",
                )
            }
            assertTrue(fixture.methods.isEmpty())
            assertFalse(sent)
        }
    }

    @Test
    fun unsafeOrOversizedCredentialsAreRejectedWithoutLeakingValues() {
        val invalid =
            listOf(
                "",
                "x".repeat(4097),
                "bad token",
                "bad\ttoken",
                "bad\r\ntoken",
                "bad\u0000token",
                "bad\u007ftoken",
                "bad\u0080token",
                "中文",
            )
        invalid.forEach { value ->
            val failure = assertThrows(McpCredentialFormatException::class.java) { requireBearerCredential(value) }
            if (value.isNotEmpty()) assertFalse(failure.toString().contains(value))
        }
    }
}
