package com.helix.app.connector

import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolCallState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Explicit external-service opt-in; two runs separated by force-stop in the HXA-125 driver. */
@RunWith(AndroidJUnit4::class)
class ConnectorExternalDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val service get() = container.connectorService
    private val marker get() = app.filesDir.toPath().resolve("connector-external-recovery.txt")
    private val arguments = """{"query":"Cloudflare Workers documentation"}"""

    @Before
    fun requireExplicitExternalRun() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectorExternal") == "true")
    }

    @Test
    fun seedRealConnectionAndDispatch() {
        val root = app.filesDir.toPath().resolve("workspaces")
        Files.createDirectories(root)
        val input = Files.createTempFile(root, "public-docs-", ".json")
        val record =
            try {
                Files.write(
                    input,
                    """{"docs":{"type":"http","url":"https://docs.mcp.cloudflare.com/mcp"}}""".toByteArray(),
                )
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", input.toFile())
                service.install(service.preview(uri))
            } finally {
                Files.delete(input)
            }
        var seeded = false
        try {
            val endpoint = record.endpoints.single()
            assertFalse(service.enabled(endpoint))
            connect(record)
            val denied = dispatch(record, approve = false)
            assertTrue("user denial must stop the call: $denied", denied is ToolDispatchOutcome.Denied)
            assertSucceeded(dispatch(record, approve = true))
            val descriptor = descriptor(record)
            val captured = container.toolPipeline.implementations.resolve(descriptor.name, descriptor.version)
            service.disable(endpoint)
            assertFalse(service.enabled(endpoint))
            assertFalse(
                container.toolPipeline.registry
                    .all()
                    .any { it.name == descriptor.name },
            )
            val stale =
                captured.execute(
                    ExecutableToolCall(
                        "external-stale",
                        descriptor.name.value,
                        descriptor.version.value.toString(),
                        Json.parseToJsonElement(arguments) as JsonObject,
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(30),
                        NoCancellation,
                    ),
                )
            assertTrue("disabled executor must refuse: $stale", stale is ToolExecutorResult.Failed)
            connect(record)
            Files.write(marker, "${record.id}\n${Process.myPid()}".toByteArray())
            seeded = true
        } finally {
            if (!seeded) service.remove(record)
        }
    }

    @Test
    fun recoverRealConnectionInNewProcess() {
        val lines = Files.readAllLines(marker)
        assertNotEquals("must be a new process", lines[1].toInt(), Process.myPid())
        val record = service.list().single { it.id == lines[0] }
        try {
            assertFalse("startup must not activate remote tools", service.enabled(record.endpoints.single()))
            assertTrue(
                container.toolPipeline.registry
                    .all()
                    .none { belongsTo(it.origin, record) },
            )
            connect(record)
            assertSucceeded(dispatch(record, approve = true))
        } finally {
            service.remove(record)
            Files.delete(marker)
        }
        assertFalse(service.list().any { it.id == record.id })
        assertTrue(
            container.toolPipeline.registry
                .all()
                .none { belongsTo(it.origin, record) },
        )
    }

    private fun connect(record: InstalledConnector) {
        val endpoint = record.endpoints.single()
        val snapshot = runBlocking { withTimeout(90_000) { service.test(endpoint, "") } }
        assertTrue(snapshot.metadata.tools.any { it.name == "search_cloudflare_documentation" })
        service.enable(endpoint, snapshot, setOf("search_cloudflare_documentation"))
        assertTrue(service.enabled(endpoint))
        println("HXA-125 Android protocol=${snapshot.identity.negotiatedProtocolVersion}; pid=${Process.myPid()}")
    }

    private fun belongsTo(
        origin: ToolOrigin,
        record: InstalledConnector,
    ): Boolean = (origin as? ToolOrigin.McpOrigin)?.serverId == record.endpoints.single().id

    private fun descriptor(record: InstalledConnector) =
        container.toolPipeline.registry
            .all()
            .single { belongsTo(it.origin, record) }

    private fun dispatch(
        record: InstalledConnector,
        approve: Boolean,
    ): ToolDispatchOutcome {
        val id = UUID.randomUUID().toString()
        val session = "external-session-$id"
        val turn = "external-turn-$id"
        val call = "external-call-$id"
        val now = System.currentTimeMillis()
        container.storage.sessions.create(session, "Public docs acceptance", null, null, now)
        container.storage.turns.start(turn, session, now)
        container.chatService.openSession(session)
        val task =
            FutureTask { container.chatService.dispatchToolCall(call, turn, descriptor(record).name.value, arguments) }
        Thread(task, "hxa125-dispatch").apply { isDaemon = true }.start()
        try {
            val deadline = System.currentTimeMillis() + 30_000
            while (!task.isDone && container.storage.approvals.byToolCall(call) == null &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            val approval =
                checkNotNull(
                    container.storage.approvals.byToolCall(call),
                ) { "expected exact approval for public query" }
            if (approve) {
                container.chatService.approveApproval(
                    approval.id,
                )
            } else {
                container.chatService.denyApproval(approval.id)
            }
            val result = task.get(90, TimeUnit.SECONDS)
            assertTrue(
                container.storage.auditEvents
                    .recent(1000)
                    .any { it.correlationId == call },
            )
            if (approve) {
                assertEquals(
                    ToolCallState.COMPLETED.name,
                    container.storage.toolCalls
                        .resolve(call)
                        .state,
                )
            }
            val storedApproval = container.storage.approvals.resolve(approval.id)
            assertEquals(if (approve) "APPROVED" else "DENIED", storedApproval.decision)
            assertEquals(approve, storedApproval.consumedAt != null)
            assertEquals(if (approve) 1 else 0, container.mcpService.sentSummaries(session).size)
            return result
        } finally {
            if (!task.isDone) {
                container.storage.approvals
                    .byToolCall(call)
                    ?.let { container.toolPipeline.broker.cancel(it.id) }
                task.cancel(true)
            }
        }
    }

    private fun assertSucceeded(result: ToolDispatchOutcome) {
        assertTrue("real dispatch must succeed: $result", result is ToolDispatchOutcome.Succeeded)
        val payload = (result as ToolDispatchOutcome.Succeeded).result.payload
        val body = Json.parseToJsonElement(payload).jsonObject
        assertEquals("false", body.getValue("isError").jsonPrimitive.content)
        assertTrue(
            body.getValue("blocks").jsonArray.any {
                val block = it.jsonObject
                block["kind"]?.jsonPrimitive?.content == "text" &&
                    !block["text"]?.jsonPrimitive?.content.isNullOrBlank()
            },
        )
    }
}
