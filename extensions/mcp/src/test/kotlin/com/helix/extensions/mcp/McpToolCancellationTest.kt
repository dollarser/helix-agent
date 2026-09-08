package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class McpToolCancellationTest {
    @Test
    fun interruptedExecutorReleasesInFlightToolStream() = verifyCancellation(interrupt = true)

    @Test
    fun deadlineReleasesInFlightToolStream() = verifyCancellation(interrupt = false)

    private fun verifyCancellation(interrupt: Boolean) {
        McpFixture(holdToolStream = true).use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            try {
                val future =
                    executor.submit {
                        try {
                            caller(fixture).call(call(if (interrupt) 30 else 3), "echo")
                        } catch (error: Exception) {
                            failure.set(error)
                        } finally {
                            finished.countDown()
                        }
                    }
                assertTrue("tools/call did not reach server", fixture.toolStarted.await(5, TimeUnit.SECONDS))
                if (interrupt) assertTrue(future.cancel(true))
                assertTrue("runtime stayed blocked after cancellation", finished.await(5, TimeUnit.SECONDS))
                assertTrue(
                    "unexpected runtime outcome: ${failure.get()}",
                    failure.get() is InterruptedException || failure.get() is CancellationException,
                )
                assertTrue("HTTP stream remained connected", fixture.toolDisconnected.await(3, TimeUnit.SECONDS))
                assertEquals("cancelled tool must not be replayed", 1, fixture.toolRequestBodies.size)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun caller(fixture: McpFixture): McpToolCaller =
        McpToolRuntime(
            credentials = McpCredentialLookup { error("keyless fixture must not request credentials") },
            endpointGate =
                McpEndpointGate { endpoint ->
                    McpNetworkPermit(endpoint.host, listOf(byteArrayOf(127, 0, 0, 1)))
                },
            clientName = "helix-test",
            clientVersion = "1",
        ).caller(McpServerConfig.disabled("cancel-fixture", fixture.endpoint).copy(enabled = true))

    private fun call(timeoutSeconds: Long) =
        ExecutableToolCall(
            toolCallId = "cancel-fixture-call",
            toolName = "echo",
            toolVersion = "1",
            args = buildJsonObject {},
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(timeoutSeconds),
            cancel = NoCancellation,
        )
}
