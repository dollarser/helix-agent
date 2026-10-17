package com.helix.app.marketplace

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.plugin.PluginService
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.provider.InAppMcpServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.repository.MessageRepository
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

/** Explicit live model profile, foreign Skill guidance and real loopback MCP; no user accounts. */
class MarketplaceReuseModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // One owned import/model journey and complete cleanup.
    fun modelUsesImportedGuideAndCurrentMcpName() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            require(AdvancedProfileAvailability.ADVANCED_AVAILABLE) { "Loopback MCP fixture requires Developer" }
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            removeInterruptedFixtureProvider(c, args.getString("marketOrphanProviderId"))
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Market reuse fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                )
            val root =
                Files.createTempDirectory(
                    ApplicationProvider.getApplicationContext<HelixApplication>().cacheDir.toPath(),
                    "market-model-",
                )
            val market = MarketplaceService(c.pluginService, c.skillRepository, MarketplaceSourceStore(root))
            val name = "market-guide-${UUID.randomUUID().toString().take(8)}"
            val previousProfile = c.profileStore.profile
            var installedId: String? = null
            var session: String? = null
            InAppMcpServer().use { server ->
                server.start()
                val origin = "http://127.0.0.1:${server.port}"
                try {
                    check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    val entries =
                        mapOf(
                            ".claude-plugin/marketplace.json" to
                                """{"name":"$name-market","plugins":[
                                        {"name":"$name","source":"./pkg"}]}""",
                            "pkg/.codex-plugin/plugin.json" to """{"name":"$name","version":"1.0"}""",
                            "pkg/.mcp.json" to
                                """{"mcpServers":{"docs":{"type":"http","url":"$origin/mcp"}}}""",
                            "pkg/skills/$name/SKILL.md" to
                                (
                                    "---\nname: $name\ndescription: Read the synthetic MCP marker\n---\n" +
                                        "# Marker workflow\nDiscover the fixture_read action, then call its current " +
                                        "Helix tool with case=market-reuse. A desktop example name is " +
                                        "mcp__docs__fixture_read; it is only a discovery hint, not a callable alias. " +
                                        "Return the exact marker from the tool response; never invent it."
                                ),
                        )
                    // Exercise the same catalog projection and installer as the UI, with no network download.
                    val files = root.resolve("input.zip")
                    java.util.zip.ZipOutputStream(Files.newOutputStream(files)).use { zip ->
                        entries.forEach { (path, text) ->
                            zip.putNextEntry(java.util.zip.ZipEntry(path))
                            zip.write(text.toByteArray())
                            zip.closeEntry()
                        }
                    }
                    val preview = market.imports.previewSource(android.net.Uri.fromFile(files.toFile()))
                    market.imports.addSource(preview)
                    val item = market.items().single { it.imported?.entry?.name == name }
                    val installed = market.imports.installCandidate(item, market.imports.previewItem(item), null)
                    installedId = installed.id
                    c.pluginService.setSkillEnabled(installed.skills.single(), true)
                    c.profileStore.switchTo(SafetyProfile.ADVANCED)
                    c.lanScopeStore.add(origin)
                    val endpoint = installed.endpoints.single()
                    c.pluginService.enable(endpoint, c.pluginService.test(endpoint, ""), setOf("fixture_read"))
                    val id = c.chatService.createSession("Imported market model fixture", provider, model)
                    session = id
                    c.pluginService.selectFromUser(id, installed.id, true)
                    c.chatService.openTestSessionControl(id, AgentMode.ACT, TurnBudgets(maxOutputTokens = 32768))
                    c.chatService.sendTestMessage(
                        id,
                        "Read the selected $name Skill using skills.list and skills.read with exact identity. " +
                            "Execute its marker workflow. Use tools.search with its desktop example name to " +
                            "find the current tool; do not call the desktop alias. Only that synthetic MCP " +
                            "read is authorized. Report the exact returned marker. Do not use phone or file tools.",
                    )
                    val deadline = android.os.SystemClock.elapsedRealtime() + 240000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        val turn =
                            c.storage.turns
                                .listBySession(id)
                                .lastOrNull()
                        if (turn != null) {
                            c.storage.toolCalls
                                .listByTurn(turn.id)
                                .filter {
                                    it.name == "mcp.${endpoint.id}.fixture_read" && it.argsJson.contains("market-reuse")
                                }.forEach { call ->
                                    c.storage.approvals.byToolCall(call.id)?.let { approval ->
                                        c.chatService.approveApproval(approval.id)
                                    }
                                }
                            if (TurnState.valueOf(turn.state).isTerminal) break
                        }
                        delay(250)
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(id)
                            .last()
                    val calls = c.storage.toolCalls.listByTurn(turn.id)
                    println("MARKET_MODEL_TRAJECTORY=" + calls.joinToString { "${it.name}:${it.state}" })
                    assertEquals(turn.errorCode, "COMPLETED", turn.state)
                    verifyCalls(calls, "mcp.${endpoint.id}.fixture_read")
                    verifyFinalAnswer(c.storage.messages, id)
                    println("MARKET_MODEL=PASS model=$model calls=${calls.size}")
                } finally {
                    stopOwnedSession(c, session)
                    removeOwnedPlugin(c.pluginService, installedId)
                    c.lanScopeStore.remove(origin)
                    c.profileStore.switchTo(previousProfile)
                    c.providerService.delete(provider)
                    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }

    private suspend fun removeInterruptedFixtureProvider(
        c: AppContainer,
        id: String?,
    ) {
        if (id == null) return
        val owned = requireNotNull(c.storage.providerConfigs.find(id))
        require(owned.displayName == "Market reuse fixture" && owned.secretAlias == null)
        require(owned.endpoint == "http://127.0.0.1:30008/v1")
        c.providerService.delete(id)
    }

    private fun verifyCalls(
        calls: List<ToolCallEntity>,
        actualMcpName: String,
    ) {
        for (tool in listOf("skills.read", "tools.search", actualMcpName)) {
            assertTrue("Missing $tool", calls.any { it.name == tool && it.state == "COMPLETED" })
        }
        assertTrue(calls.none { it.name.startsWith("mcp__") || it.name.startsWith("ui.") })
    }

    private fun verifyFinalAnswer(
        messages: MessageRepository,
        sessionId: String,
    ) {
        val finalMessage =
            messages.listBySession(sessionId).last {
                it.role == "ASSISTANT" && it.kind == "TEXT"
            }
        assertTrue(messages.readContent(finalMessage).orEmpty().contains("SYNTHETIC_READ_OK"))
    }

    private fun removeOwnedPlugin(
        service: PluginService,
        id: String?,
    ) {
        if (id != null) service.list().filter { it.id == id }.forEach(service::remove)
    }

    private suspend fun stopOwnedSession(
        c: AppContainer,
        id: String?,
    ) {
        if (id == null) return
        c.storage.turns.listBySession(id).lastOrNull()?.let { turn ->
            c.chatService.stopTurn(turn.id)
            // Cancel accepts a stop request; wait for the live observation before deleting its Room facts.
            withTimeout(20_000) { c.agentRuntime.observe(TurnId(turn.id)).collect {} }
            assertTrue(TurnState.valueOf(requireNotNull(c.storage.turns.find(turn.id)).state).isTerminal)
        }
        c.chatService.closeSession()
        c.storage.deleteSessionPermanently(id)
    }
}
