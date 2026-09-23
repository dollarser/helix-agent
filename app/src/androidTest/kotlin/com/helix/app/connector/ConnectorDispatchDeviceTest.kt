package com.helix.app.connector

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.skills.connector.ConnectorPackageReader
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A controlled executor exercises the real dispatcher/approval/storage boundary, not remote effects. */
class ConnectorDispatchDeviceTest {
    @Test
    @Suppress("LongMethod") // one pending approval lifecycle with guaranteed broker cleanup
    fun sourceAndToolDisablesApplyToExposureAndPendingApprovalWithoutDeletingHistory() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val session = "selection-${UUID.randomUUID()}"
        val turn = "turn-${UUID.randomUUID()}"
        val call = "call-${UUID.randomUUID()}"
        val record =
            c.connectorService.install(
                ConnectorPackageReader().readJson(
                    """{"mcp_servers":{"fixture":{"url":"https://example.com/dispatch"}}}""".toByteArray(),
                ),
                identity = session,
            )
        val id = record.endpoints.single().id
        val descriptor = descriptor(id)
        val count = AtomicInteger()
        val pipeline = c.toolPipeline
        c.storage.sessions.create(session, "Selection", null, null, 0)
        c.storage.messages.append("$session-message", session, null, "USER", "TEXT", "Keep this history")
        c.storage.turns.start(turn, session, 0)
        c.chatService.openSession(session)
        c.connectorService.catalog.select(session, record.id, true)
        c.sessionPermissionEdit.saveSessionConfig(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
            0,
        )
        pipeline.registry.register(descriptor)
        pipeline.implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: com.helix.tools.framework.ExecutableToolCall): ToolExecutorResult {
                    count.incrementAndGet()
                    return ToolExecutorResult.Completed(JsonObject(emptyMap()))
                }
            },
        )
        var pending: String? = null
        val result = CompletableFuture<ToolDispatchOutcome>()
        try {
            assertTrue(descriptor in pipeline.mcpDiscovery.visible(session, listOf(descriptor)))
            Thread {
                try {
                    result.complete(c.chatService.dispatchToolCall(call, turn, descriptor.name.value, "{}"))
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }.apply {
                isDaemon = true
                start()
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (pending == null && System.currentTimeMillis() < deadline && !result.isDone) {
                pending =
                    c.storage.approvals
                        .byToolCall(call)
                        ?.id
                if (pending == null) Thread.sleep(25)
            }
            check(pending != null) { "No pending approval: ${if (result.isDone) result.get() else "timeout"}" }
            c.connectorService.catalog.select(session, record.id, false)
            assertTrue(pipeline.mcpDiscovery.visible(session, listOf(descriptor)).isEmpty())
            c.chatService.approveApproval(pending)
            val outcome = result.get(30, TimeUnit.SECONDS)
            assertTrue(outcome.toString(), outcome is ToolDispatchOutcome.Denied)
            assertEquals(DispatchOutcomeCode.TOOL_DISABLED, (outcome as ToolDispatchOutcome.Denied).code)
            assertEquals(0, count.get())
            assertEquals(
                1,
                c.storage.messages
                    .listBySession(session)
                    .count { it.role == "USER" },
            )
            c.connectorService.catalog.select(session, record.id, true)
            c.sessionPermissionEdit.setToolAvailability(
                descriptor.origin.canonicalOf(),
                descriptor.name.value,
                ToolAvailabilityScope.SESSION,
                session,
                true,
                1,
            )
            assertTrue(pipeline.mcpDiscovery.visible(session, listOf(descriptor)).isEmpty())
        } finally {
            pending?.let { pipeline.broker.cancel(it) }
            if (!result.isDone) runCatching { result.get(30, TimeUnit.SECONDS) }
            pipeline.registry.replaceMcpServer(id, emptyList())
            pipeline.implementations.replaceMcpServer(id, emptyList())
            pipeline.endTurn(turn)
            c.chatService.closeSession()
            c.connectorService.remove(record)
            c.storage.deleteSessionPermanently(session)
        }
    }

    private fun descriptor(server: String) =
        ToolDescriptor(
            name = ToolName("mcp.$server.fixture"),
            version = ToolVersion(1),
            description = "Connector dispatch fixture",
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ).jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.McpOrigin(server, "2025-03-26", "a".repeat(64)),
        )
}
