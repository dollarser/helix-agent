package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.time.Instant

class McpTechnicalRecoveryTest {
    private val gate =
        McpEndpointGate { endpoint ->
            McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
        }
    private val credentials = McpCredentialLookup { error("keyless fixture") }

    private fun call(
        millis: Long = 4000,
        cancel: CancelSignal = NoCancellation,
    ) = ExecutableToolCall(
        "call",
        "echo",
        "1",
        buildJsonObject {},
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusMillis(millis),
        cancel,
    )

    private fun config(fixture: McpFixture) =
        McpServerConfig.disabled("recovery", fixture.endpoint).copy(enabled = true)

    @Test fun lostBootstrapReplyRecoversBeforeExactlyOneBusinessPost() {
        McpFixture(dropInitializeResponses = 1).use { fixture ->
            val pauses = mutableListOf<Long>()
            val runtime = McpToolRuntime(credentials, gate, "helix-test", "1", retryDelay = { pauses += it })
            val result = runtime.caller(config(fixture)).call(call(), "echo")
            assertEquals(2, fixture.initializeCount.get())
            assertEquals(1, fixture.toolRequestBodies.size)
            assertEquals(2, result.connectionAttempts)
            assertEquals(1, pauses.size)
            assertTrue(pauses.single() in 800..1200)
        }
    }

    @Test fun lostBusinessReplyRemainsUnknownAndIsNotReplayed() {
        McpFixture(dropToolResponse = true).use { fixture ->
            val runtime = McpToolRuntime(credentials, gate, "helix-test", "1", retryDelay = {})
            val failure = runCatching { runtime.caller(config(fixture)).call(call(), "echo") }.exceptionOrNull()
            assertTrue(failure is McpCallProgress)
            assertTrue((failure as McpCallProgress).requestSent)
            assertEquals(1, failure.connectionAttempts)
            assertEquals(1, fixture.toolRequestBodies.size)
        }
    }

    @Test fun busyBusinessPostDoesNotFollowRetryAfterZero() {
        McpFixture(toolStatus = 503).use { fixture ->
            val runtime = McpToolRuntime(credentials, gate, "helix-test", "1", retryDelay = {})
            val failure = runCatching { runtime.caller(config(fixture)).call(call(), "echo") }.exceptionOrNull()
            assertTrue(failure is McpCallProgress && failure.requestSent)
            assertEquals(1, fixture.toolRequestBodies.size)
        }
    }

    @Test fun cancellationDuringBackoffPreventsAnyToolOrSecondConnection() {
        McpFixture().use { fixture ->
            var cancelled = false
            var attempts = 0
            val runtime =
                McpToolRuntime(
                    credentials,
                    gate,
                    "helix-test",
                    "1",
                    clientFactory = { _, _, _ ->
                        attempts++
                        throw ConnectException("temporary")
                    },
                    retryDelay = { cancelled = true },
                )
            val cancel =
                object : CancelSignal {
                    override fun isCancelled() = cancelled
                }
            val failure =
                assertThrows(McpCallCancellation::class.java) {
                    runtime.caller(config(fixture)).call(call(cancel = cancel), "echo")
                }
            assertFalse(failure.requestSent)
            assertEquals(1, attempts)
            assertTrue(fixture.methods.isEmpty())
        }
    }

    @Test fun originalDeadlineCancelsBootstrapBeforeBusinessSubmission() {
        McpFixture().use { fixture ->
            val runtime =
                McpToolRuntime(
                    credentials,
                    gate,
                    "helix-test",
                    "1",
                    clientFactory = { _, _, _ ->
                        object : McpClientFacade {
                            override suspend fun connect(endpointUrl: String): McpClientSession {
                                delay(2000)
                                error("deadline should cancel connection")
                            }
                        }
                    },
                )
            val failure =
                assertThrows(McpCallCancellation::class.java) {
                    runtime.caller(config(fixture)).call(call(40), "echo")
                }
            assertTrue(failure.deadlineExceeded)
            assertFalse(failure.requestSent)
            assertTrue(fixture.methods.isEmpty())
        }
    }

    @Test fun insufficientRemainingBudgetDoesNotStartAnotherAttempt() {
        McpFixture().use { fixture ->
            var attempts = 0
            val runtime =
                McpToolRuntime(
                    credentials,
                    gate,
                    "helix-test",
                    "1",
                    clientFactory = { _, _, _ ->
                        attempts++
                        throw ConnectException("temporary")
                    },
                    retryDelay = { error("no time for another attempt") },
                )
            val failure =
                assertThrows(McpCallException::class.java) {
                    runtime.caller(config(fixture)).call(call(500), "echo")
                }
            assertFalse(failure.requestSent)
            assertEquals(1, attempts)
        }
    }

    @Test fun cleanupFailurePreservesReceivedResultInsteadOfReportingAnUnknownAction() {
        McpFixture().use { fixture ->
            val session =
                object : McpClientSession {
                    override val server = McpServerIdentity("fixture", "1", "2025-03-26")

                    override suspend fun ping() = error("not used")

                    override suspend fun snapshotMetadata(limits: McpMetadataLimits) = error("not used")

                    override suspend fun callTool(
                        name: String,
                        arguments: kotlinx.serialization.json.JsonObject,
                        limits: McpToolResultLimits,
                    ) = McpToolResult(false, listOf(McpResultBlock.Text("received")), null)

                    override suspend fun close(): Unit = throw IOException("cleanup failed")
                }
            val runtime =
                McpToolRuntime(
                    credentials,
                    gate,
                    "helix-test",
                    "1",
                    clientFactory = { _, _, _ ->
                        object : McpClientFacade {
                            override suspend fun connect(endpointUrl: String) = session
                        }
                    },
                )
            val result = runtime.caller(config(fixture)).call(call(), "echo")
            assertEquals(listOf(McpResultBlock.Text("received")), result.blocks)
            assertFalse(result.connectionClosed)
            assertEquals(1, result.connectionAttempts)
        }
    }
}
