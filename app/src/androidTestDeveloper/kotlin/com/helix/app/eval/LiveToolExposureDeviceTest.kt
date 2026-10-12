package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real model; transient MCP-origin executors have no external side effects. */
class LiveToolExposureDeviceTest {
    private val terminalStates = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")

    @Test
    @Suppress("LongMethod") // One real request path with owned provider/session/registration cleanup.
    fun modelCallsLastToolBeyondFormerGlobalLimit(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Owner-authorized live inputs required", args.getString("liveToolExposure") == "true")
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Tool exposure fixture",
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
            var session: String? = null
            val oldProfile = c.profileStore.profile
            val server = "exposurefixture"
            val called = AtomicInteger()
            val prototype = requireNotNull(c.toolPipeline.resolveLatest("tools.search"))
            val originalTools = c.toolPipeline.registry.all()
            val disabled = mutableListOf<com.helix.tools.framework.ToolDescriptor>()
            val tools =
                (0 until 70).map { index ->
                    prototype.copy(
                        name = ToolName("mcp.$server.echo_$index"),
                        description = "Return empty fixture JSON",
                        inputSchema = Json.parseToJsonElement("{}").jsonObject,
                        outputSchema = Json.parseToJsonElement("{}").jsonObject,
                        operationClass = ToolOperationClass.NETWORK,
                        origin = ToolOrigin.McpOrigin(server, "2025-03-26", "a".repeat(64)),
                    )
                }
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val ownedSession = c.chatService.createSession("Tool exposure fixture", provider, model)
                session = ownedSession
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                originalTools.forEach { tool ->
                    c.storage.toolAvailability.set(
                        tool.origin.canonicalOf(),
                        tool.name.value,
                        ToolAvailabilityScope.SESSION,
                        ownedSession,
                        ToolAvailabilityState.DISABLED,
                        System.currentTimeMillis(),
                    )
                    disabled += tool
                }
                c.sessionPermissionEdit.saveSessionConfig(
                    ownedSession,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.toolPipeline.registry.replaceMcpServer(
                    server,
                    tools.map { tool ->
                        ToolBinding(
                            tool,
                            object : ToolExecutor {
                                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                                    if (tool == tools.last()) called.incrementAndGet()
                                    return ToolExecutorResult.Completed(buildJsonObject {})
                                }
                            },
                        )
                    },
                )
                val turn =
                    c.agentRuntime
                        .submit(
                            SubmitTurnCommand(
                                session = SessionId(ownedSession),
                                providerId = ProviderId(provider),
                                mode = AgentMode.ACT,
                                text =
                                    "Call ${tools.last().name.value} exactly once with {}. " +
                                        "It is a synthetic no-effect fixture. " +
                                        "Do not use any other tools. Reply DONE afterwards.",
                                budgets = TurnBudgets(8, 8, 65536, 2048, 100000),
                                clientRequestId = UUID.randomUUID().toString(),
                            ),
                        ).value
                withTimeout(180000) {
                    while (c.storage.turns
                            .resolve(turn)
                            .state !in terminalStates
                    ) {
                        delay(200)
                    }
                }
                assertEquals(
                    "COMPLETED",
                    c.storage.turns
                        .resolve(turn)
                        .state,
                )
                assertEquals(1, called.get())
                val calls = c.storage.toolCalls.listByTurn(turn)
                assertEquals(listOf(tools.last().name.value), calls.map { it.name })
                assertTrue(calls.all { it.state == "COMPLETED" })
                val first =
                    c.storage.modelCalls
                        .listByTurn(turn)
                        .first()
                val manifest = Json.parseToJsonElement(requireNotNull(first.requestManifest)).jsonObject
                val bindings = manifest.getValue("tools").jsonArray
                val names =
                    bindings.map {
                        it.jsonArray
                            .first()
                            .jsonPrimitive.content
                    }
                assertTrue(names.size > 64)
                assertTrue(names.containsAll(tools.map { it.name.value }))
                println("LIVE_TOOL_EXPOSURE tools=${names.size} lastToolCalls=${called.get()} state=COMPLETED")
            } finally {
                c.toolPipeline.registry.replaceMcpServer(server, emptyList())
                session?.let { owned ->
                    c.storage.turns
                        .listBySession(owned)
                        .lastOrNull()
                        ?.let { c.chatService.stopTurn(it.id) }
                    disabled.forEach { tool ->
                        c.storage.toolAvailability.remove(
                            tool.origin.canonicalOf(),
                            tool.name.value,
                            ToolAvailabilityScope.SESSION,
                            owned,
                        )
                    }
                    c.chatService.closeSession()
                    c.storage.deleteSessionPermanently(owned)
                }
                c.profileStore.switchTo(oldProfile)
                c.providerService.delete(provider)
            }
        }
}
