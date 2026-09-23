package com.helix.app.connector

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.mcp.McpAppService
import com.helix.app.mcp.McpStorageBridge
import com.helix.app.provider.InAppMcpServer
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SecretAlias
import com.helix.core.policy.NetworkOriginScope
import com.helix.extensions.skills.connector.ConnectorPackageReader
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Protocol fixtures explicitly provide an Advanced LAN permit; they do not grant it to the consumer app. */
class ConnectorSendBoundaryDeviceTest {
    @Test
    fun capturedCallIsCancelledBeforeConnectionOrBeforeToolSendWhenSelectionCloses() {
        withFixture { fixture ->
            var connections = 0
            var sends = 0
            fixture.onMethod.set { method ->
                if (method == "initialize") connections++
                if (method == "tools/call") sends++
            }
            fixture.select(false)
            assertEquals(ToolExecutorResult.Cancelled, fixture.execute())
            assertEquals(0, connections)
            fixture.select(true)
            fixture.onMethod.set { method ->
                if (method == "initialize") {
                    connections++
                    fixture.select(false)
                }
                if (method == "tools/call") sends++
            }
            assertEquals(ToolExecutorResult.Cancelled, fixture.execute())
            assertEquals(1, connections)
            assertEquals(0, sends)
        }
    }

    @Test
    fun sentCallSettlesAfterUninstallAndCredentialCleanupWaitsForItsReference() {
        withFixture { fixture ->
            fixture.onMethod.set { method ->
                if (method == "tools/call") {
                    fixture.service.remove(fixture.record)
                    assertTrue(fixture.service.cleanupPending)
                    assertEquals("synthetic-live", fixture.secret())
                }
            }
            val result = fixture.execute()
            assertTrue(result.toString(), result is ToolExecutorResult.Completed)
            assertTrue(result.toString().contains(InAppMcpServer.READ_TEXT))
            fixture.service.cleanupRetired()
            assertFalse(fixture.service.cleanupPending)
            assertTrue(runCatching { fixture.secret() }.isFailure)
            assertEquals(ToolExecutorResult.Cancelled, fixture.execute())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val callback = AtomicReference<(String) -> Unit>({})
        InAppMcpServer { callback.get()(it) }.use { server ->
            server.start()
            val fixture = Fixture(server.port, callback)
            try {
                block(fixture)
            } finally {
                callback.set {}
                fixture.close()
            }
        }
    }

    private class Fixture(
        port: Int,
        val onMethod: AtomicReference<(String) -> Unit>,
    ) : AutoCloseable {
        private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        private val c = app.appContainer
        private val registry = ToolRegistry()
        private val implementations = ToolImplementationRegistry()
        private val session = "connector-send-${UUID.randomUUID()}"
        private val mcp =
            McpAppService(
                McpStorageBridge(c.storage),
                { SafetyProfile.ADVANCED },
                registry,
                implementations,
                lanScopes = { setOf(NetworkOriginScope("127.0.0.1", port)) },
                sourceAvailable = c.connectorService.catalog::endpointAvailable,
            )
        val service =
            ConnectorService(
                app,
                c.storage,
                mcp,
                c.skillImportService,
                c.skillRepository,
                catalog = c.connectorService.catalog,
            )
        val record =
            service.install(
                ConnectorPackageReader()
                    .readJson(
                        """{"mcp_servers":{"send":{"url":"https://example.com/mcp"}}}""".toByteArray(),
                    ).let { bundle ->
                        // Import only accepts portable HTTPS; substitute the explicitly scoped local protocol fixture.
                        bundle.copy(endpoints = bundle.endpoints.map { it.copy(url = "http://127.0.0.1:$port/mcp") })
                    },
            )
        private val endpoint = record.endpoints.single()
        private val descriptor: com.helix.tools.framework.ToolDescriptor
        private val captured: com.helix.tools.framework.ToolExecutor

        init {
            c.storage.sessions.create(session, "Send boundary", null, null, 0)
            select(true)
            c.storage.secrets.put(SecretAlias(endpoint.id), "synthetic-live")
            mcp.registerDisabled(endpoint.id, endpoint.endpoint.url, null)
            val snapshot = runBlocking { mcp.testConnection(endpoint.id) }
            mcp.enable(snapshot, setOf(InAppMcpServer.TOOL_NAME))
            descriptor = registry.all().single()
            captured = implementations.resolve(descriptor.name, descriptor.version)
        }

        fun select(enabled: Boolean) = service.catalog.select(session, record.id, enabled)

        fun secret(): String = c.storage.secrets.get(SecretAlias(endpoint.id))

        fun execute(): ToolExecutorResult =
            captured.execute(
                ExecutableToolCall(
                    UUID.randomUUID().toString(),
                    descriptor.name.value,
                    descriptor.version.value.toString(),
                    Json.parseToJsonElement("""{"case":"fixture"}""").jsonObject,
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(20),
                    NoCancellation,
                    sessionId = session,
                ),
            )

        override fun close() {
            service.list().filter { it.id == record.id }.forEach(service::remove)
            service.cleanupRetired()
            c.storage.deleteSessionPermanently(session)
        }
    }
}
