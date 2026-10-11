package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.provider.InAppMcpServer
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.skills.SkillSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Author examples use real parsers, installation, selection and dispatcher; no remote account. */
class PluginAuthorExamplesDeviceTest {
    private val c get() = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer

    @Test
    @Suppress("LongMethod") // One complete production content journey and its cleanup.
    fun pureWorkflowPreviewInstallSelectReadAndDisable() {
        val name = "pure-${UUID.randomUUID()}"
        val manifest = """{
            "${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$name"
        }"""
        val bundle =
            PluginPackageReader().parse(
                mapOf(
                    "plugin.json" to manifest.toByteArray(),
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Draft supplied text\n---\nDraft only".toByteArray(),
                ),
            )
        val record = c.pluginService.install(bundle, "fixture:$name")
        val session = UUID.randomUUID().toString()
        val turn = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Pure author example", null, null, 0)
        c.storage.turns.start(turn, session, 0)
        c.chatService.openSession(session)
        try {
            assertEquals(bundle.componentDiagnostics, record.componentDiagnostics)
            assertTrue(record.endpoints.isEmpty())
            c.pluginService.setSkillEnabled(record.skills.single(), true)
            c.pluginService.selectFromUser(session, record.id, true)
            val key = record.skills.single()
            val args =
                buildJsonObject {
                    put("source", key.source.name)
                    put("name", key.name)
                    put("snapshotHash", key.snapshotHash)
                }.toString()

            fun read() =
                c.chatService.dispatchToolCall(
                    UUID.randomUUID().toString(),
                    turn,
                    "skills.read",
                    args,
                    mode = AgentMode.ACT,
                )
            val result = read()
            assertTrue(result.toString(), result is ToolDispatchOutcome.Succeeded)
            assertTrue(result.toString().contains("Draft only"))
            c.pluginService.selectFromUser(session, record.id, false)
            assertFalse(read() is ToolDispatchOutcome.Succeeded)
        } finally {
            c.toolPipeline.endTurn(turn)
            c.chatService.closeSession()
            c.pluginService.remove(record)
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // Protocol/socket lifecycle and explicit cleanup form one journey.
    fun remoteMcpPreviewInstallConnectCallAndDisable() =
        runBlocking {
            val methods = AtomicInteger()
            InAppMcpServer { methods.incrementAndGet() }.use { server ->
                server.start()
                val name = "remote-${UUID.randomUUID()}"
                val manifest = """{
                "${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                "name":"$name"
            }"""
                val mcp = """{
                "${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                "mcpServers":{"docs":{"type":"streamable-http","url":"http://127.0.0.1:${server.port}/mcp"}}
            }"""
                val preview =
                    PluginPackageReader().parse(
                        mapOf(
                            "plugin.json" to manifest.toByteArray(),
                            "mcp.json" to mcp.toByteArray(),
                        ),
                    )
                val record = c.pluginService.install(preview, "fixture:$name")
                val endpoint = record.endpoints.single()
                val session = UUID.randomUUID().toString()
                val turn = UUID.randomUUID().toString()
                c.storage.sessions.create(session, "MCP author example", null, null, 0)
                c.storage.turns.start(turn, session, 0)
                c.chatService.openSession(session)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    0,
                )
                val previousProfile = c.profileStore.profile
                val origin = "http://127.0.0.1:${server.port}"
                try {
                    c.profileStore.switchTo(SafetyProfile.ADVANCED)
                    c.lanScopeStore.add(origin)
                    assertEquals(preview.componentDiagnostics, record.componentDiagnostics)
                    repeat(3) { c.pluginService.requestReadiness(session, turn) }
                    assertEquals(0, methods.get())
                    val handshake = c.pluginService.test(endpoint, "")
                    c.pluginService.enable(endpoint, handshake, setOf("fixture_read"))
                    c.pluginService.selectFromUser(session, record.id, true)
                    val state = c.pluginService.sessionRows(session).single { it.id == record.id }
                    assertTrue(state.ready)
                    val fact = requireNotNull(state.readiness).components.single()
                    assertTrue(fact.saved)
                    assertTrue(fact.loaded)
                    val descriptor =
                        c.toolPipeline.registry.all().single {
                            val origin = it.origin as? com.helix.tools.framework.ToolOrigin.McpOrigin
                            origin?.serverId == endpoint.id && it.name.value.endsWith("fixture_read")
                        }
                    val call = UUID.randomUUID().toString()
                    val args = """{"case":"author-example"}"""
                    val pending =
                        CompletableFuture.supplyAsync {
                            c.chatService.dispatchToolCall(
                                call,
                                turn,
                                descriptor.name.value,
                                args,
                                mode = AgentMode.ACT,
                            )
                        }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (!pending.isDone && System.nanoTime() < deadline) {
                        c.storage.approvals.byToolCall(call)?.let {
                            c.chatService.approveApproval(it.id)
                        }
                        Thread.sleep(50)
                    }
                    val result = pending.get(20, TimeUnit.SECONDS)
                    assertTrue(result.toString(), result is ToolDispatchOutcome.Succeeded)
                    assertTrue(result.toString().contains("SYNTHETIC_READ_OK"))
                    val count = methods.get()
                    c.pluginService.selectFromUser(session, record.id, false)
                    val denied =
                        c.chatService.dispatchToolCall(
                            UUID.randomUUID().toString(),
                            turn,
                            descriptor.name.value,
                            args,
                            mode = AgentMode.ACT,
                        )
                    assertFalse(denied is ToolDispatchOutcome.Succeeded)
                    assertEquals(count, methods.get())
                    c.pluginService.disable(endpoint)
                    assertFalse(c.mcpService.isActive(endpoint.id))
                    assertFalse(c.pluginService.componentReadiness(record).fullyReady)
                } finally {
                    c.toolPipeline.endTurn(turn)
                    c.chatService.closeSession()
                    c.pluginService.remove(record)
                    c.storage.deleteSessionPermanently(session)
                    c.lanScopeStore.remove(origin)
                    c.profileStore.switchTo(previousProfile)
                }
            }
        }

    @Test
    @Suppress("LongMethod") // Import rejection and actual APK contribution are verified together.
    fun onlyApkCompositionCanSupplyTheKnownNativeExample() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val manifest = app.assets.open("plugins/chat-reply/plugin.json").use { it.readBytes() }
        val bytes = app.assets.open("plugins/chat-reply/skills/chat-reply/SKILL.md").use { it.readBytes() }
        val preview =
            PluginPackageReader().parse(
                mapOf(
                    "plugin.json" to manifest,
                    "skills/chat-reply/SKILL.md" to bytes,
                ),
            )
        assertTrue(preview.componentDiagnostics.any { it.kind == PluginComponentKind.NATIVE && it.omitted })
        val imported = c.pluginService.install(preview, "fixture:native-${UUID.randomUUID()}")
        val session = UUID.randomUUID().toString()
        val turn = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Native author example", null, null, 0)
        c.storage.turns.start(turn, session, 0)
        c.chatService.openSession(session)
        try {
            assertEquals(null, imported.native)
            val native = c.pluginService.list().single { it.id == "bundled-plugin:chat-reply" }
            assertEquals("chat-reply", requireNotNull(native.native).runtimeId)
            assertFalse(c.pluginRegistry.hasPublishedTools("chat-reply"))
            val key =
                c.skillRepository
                    .list()
                    .single {
                        it.key.source == SkillSource.BUILT_IN && it.key.name == "chat-reply"
                    }.key
            c.pluginService.selectFromUser(session, native.id, true)
            val args =
                buildJsonObject {
                    put("source", key.source.name)
                    put("name", key.name)
                    put("snapshotHash", key.snapshotHash)
                }.toString()
            val result =
                c.chatService.dispatchToolCall(
                    UUID.randomUUID().toString(),
                    turn,
                    "skills.read",
                    args,
                    mode = AgentMode.ACT,
                )
            assertTrue(result.toString(), result is ToolDispatchOutcome.Succeeded)
            assertTrue(result.toString().contains("Chat reply assistant"))
            c.pluginService.selectFromUser(session, native.id, false)
            val stopped =
                c.chatService.dispatchToolCall(
                    UUID.randomUUID().toString(),
                    turn,
                    "skills.read",
                    args,
                    mode = AgentMode.ACT,
                )
            assertFalse(stopped is ToolDispatchOutcome.Succeeded)
        } finally {
            c.toolPipeline.endTurn(turn)
            c.chatService.closeSession()
            c.pluginService.remove(imported)
            c.storage.deleteSessionPermanently(session)
        }
    }
}
