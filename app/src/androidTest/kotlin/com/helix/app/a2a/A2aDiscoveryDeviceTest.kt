package com.helix.app.a2a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.a2a.A2aClients
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class A2aDiscoveryDeviceTest {
    @Test
    fun productionDiscoveryUsesAliasOnlyAndPersistsExplicitSkillSelection() =
        withStorage { storage ->
            val requests = CopyOnWriteArrayList<CapturedRequest>()
            TestHttpServer(expectedRequests = 2) { index, socket, port ->
                requests += socket.readRequest()
                val body =
                    if (index == 0) {
                        card(port, extended = true, skillIds = listOf("echo", "search"))
                    } else {
                        "{\"jsonrpc\":\"2.0\",\"id\":\"helix-agent-card\",\"result\":" +
                            card(port, extended = true, skillIds = listOf("private", "search")) +
                            "}"
                    }
                socket.writeResponse(200, "application/json", body)
            }.use { server ->
                val alias = SecretAlias("a2a-device-${System.nanoTime()}")
                storage.secrets.put(alias, "device-bearer")
                try {
                    val bridge = A2aStorageBridge(storage)
                    val (service, _) = service(storage, bridge)
                    val config = service.registerDisabled("device-agent", server.cardEndpoint, alias.value)
                    assertFalse(config.enabled)

                    val snapshot = kotlinx.coroutines.runBlocking { service.testConnection("device-agent") }
                    assertTrue(snapshot.extended)
                    assertEquals(listOf("private", "search"), snapshot.skills.map { it.id })
                    assertFalse(requests[0].headers.containsKey("authorization"))
                    assertEquals("Bearer device-bearer", requests[1].headers["authorization"])
                    assertEquals("1.0", requests[1].headers["a2a-version"])
                    assertTrue(requests[1].bodyText.contains("GetExtendedAgentCard"))

                    service.enable(snapshot, setOf("private"))
                    assertTrue(storage.a2aAgents.resolve("device-agent").enabled)
                    val skills = storage.a2aCapabilities.listByAgent("device-agent")
                    assertTrue(skills.single { it.skillId == "private" }.enabled)
                    assertFalse(skills.single { it.skillId == "search" }.enabled)
                } finally {
                    storage.secrets.delete(alias)
                }
            }
        }

    @Test
    fun changedOrInvalidCardFailsClosedAndRevokesPreviousEnablement() =
        withStorage { storage ->
            val bridge = A2aStorageBridge(storage)
            val (service, registry) = service(storage, bridge)
            TestHttpServer(expectedRequests = 2) { index, socket, port ->
                socket.readRequest()
                val body =
                    if (index == 0) {
                        card(port, extended = false, skillIds = listOf("echo"))
                    } else {
                        card(port, extended = false, skillIds = listOf("changed"))
                    }
                socket.writeResponse(200, "application/json", body)
            }.use { server ->
                service.registerDisabled("changing-agent", server.cardEndpoint, null)
                val first = kotlinx.coroutines.runBlocking { service.testConnection("changing-agent") }
                service.enable(first, setOf("echo"))
                assertTrue(registry.all().any { it.name.value.startsWith("a2a.changing-agent.echo_") })

                kotlinx.coroutines.runBlocking { service.testConnection("changing-agent") }
                assertFalse(storage.a2aAgents.resolve("changing-agent").enabled)
                assertTrue(storage.a2aCapabilities.listByAgent("changing-agent").none { it.enabled })
                assertTrue(registry.all().none { it.name.value.startsWith("a2a.changing-agent.") })
            }

            val retainedHash = storage.a2aAgents.resolve("changing-agent").cardHash
            listOf(
                FailureResponse(401, "application/json", "{\"error\":\"secret-body\"}"),
                FailureResponse(200, "text/plain", "not-json"),
                FailureResponse(200, "application/json", "{\"description\":\"${"x".repeat(513 * 1024)}\"}"),
            ).forEachIndexed { index, response ->
                TestHttpServer(expectedRequests = 1) { _, socket, _ ->
                    socket.readRequest()
                    socket.writeResponse(response.status, response.contentType, response.body)
                }.use { server ->
                    val invalid = bridge.registerDisabled("invalid-agent-$index", server.cardEndpoint, null)
                    assertFails {
                        kotlinx.coroutines.runBlocking { A2aClients.discovery(bridge.credentials()).discover(invalid) }
                    }
                }
                assertEquals(retainedHash, storage.a2aAgents.resolve("changing-agent").cardHash)
            }
        }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val storage = HelixStorage.open(context, "a2a-discovery-$suffix.db", context.filesDir.resolve("a2a-$suffix"))
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase("a2a-discovery-$suffix.db")
            context.filesDir.resolve("a2a-$suffix").deleteRecursively()
        }
    }

    private fun service(
        storage: HelixStorage,
        bridge: A2aStorageBridge,
    ): Pair<A2aAppService, ToolRegistry> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspaceRoot = context.filesDir.resolve("a2a-workspace-${System.nanoTime()}").apply { mkdirs() }
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        val runner =
            A2aTaskRunner(
                storage = storage,
                workspace = WorkspaceArtifactStore(ScopeRootResolver { workspaceRoot.toPath() }),
                workspaceScopeId = "workspace",
                resolveWorkspaceFile = { error("A2A discovery fixture does not resolve artifacts") },
                client = A2aClients.task(),
            )
        return A2aAppService(bridge, registry, implementations, runner) to registry
    }

    private fun card(
        port: Int,
        extended: Boolean,
        skillIds: List<String>,
    ): String =
        """
        {
          "name":"Device Agent",
          "description":"Device discovery fixture",
          "supportedInterfaces":[
            {"url":"http://127.0.0.1:$port/legacy","protocolBinding":"JSONRPC","protocolVersion":"0.3"},
            {"url":"http://127.0.0.1:$port/a2a","protocolBinding":"JSONRPC","protocolVersion":"1.0"}
          ],
          "provider":{"organization":"Helix Fixture","url":"https://example.invalid"},
          "version":"1.0.0",
          "capabilities":{"streaming":true,"pushNotifications":false,"extendedAgentCard":$extended},
          "defaultInputModes":["text/plain"],
          "defaultOutputModes":["text/plain","application/json"],
          "skills":[${skillIds.joinToString(",") { skill(it) }}]
        }
        """.trimIndent()

    private fun skill(id: String): String =
        """{"id":"$id","name":"$id","description":"Bounded $id fixture","tags":["device"]}"""

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("expected fail-closed discovery", failed)
    }

    private data class FailureResponse(
        val status: Int,
        val contentType: String,
        val body: String,
    )

    private data class CapturedRequest(
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val bodyText: String get() = body.toString(StandardCharsets.UTF_8)
    }

    private class TestHttpServer(
        expectedRequests: Int,
        private val handler: (Int, Socket, Int) -> Unit,
    ) : Closeable {
        private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        private val thread =
            Thread {
                try {
                    repeat(expectedRequests) { index ->
                        server.accept().use { handler(index, it, server.localPort) }
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) failure.set(error)
                }
            }.apply {
                name = "a2a-discovery-device-server"
                start()
            }

        val cardEndpoint: String get() = "http://127.0.0.1:${server.localPort}/.well-known/agent-card.json"

        override fun close() {
            server.close()
            thread.join(5_000)
            check(!thread.isAlive) { "A2A fixture server did not stop" }
            failure.get()?.let { throw AssertionError("A2A fixture server failed", it) }
        }
    }

    private fun Socket.readRequest(): CapturedRequest {
        soTimeout = 5_000
        val input = BufferedInputStream(getInputStream())
        val headerBytes = ByteArrayOutputStream()
        var tail = 0
        while (tail != HEADER_END) {
            val next = input.read()
            check(next >= 0) { "request ended before headers" }
            headerBytes.write(next)
            tail = ((tail shl 8) or next) and HEADER_MASK
        }
        val lines = headerBytes.toString(StandardCharsets.ISO_8859_1.name()).trim().lines()
        val headers =
            lines.drop(1).associate { line ->
                val separator = line.indexOf(':')
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0) { "request ended before body" }
            offset += count
        }
        return CapturedRequest(headers, body)
    }

    private fun Socket.writeResponse(
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Unauthorized"
        val head =
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        BufferedOutputStream(getOutputStream()).use { output ->
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            output.write(bytes)
        }
    }

    private companion object {
        const val HEADER_END = 0x0D0A0D0A
        const val HEADER_MASK = -1
    }
}
