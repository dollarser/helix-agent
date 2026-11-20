package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.ToolArtifactRegistrationSink
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.mcp.McpResourceArtifactPublisher
import com.helix.app.proot.LogComparisonWire
import com.helix.app.proot.NaturalFixtureAvailability
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.mcp.McpClients
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpResourceTools
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpToolCaller
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Official SDK HTTP server and real model. Binary marker is never supplied in the user prompt. */
class RealBinaryMcpDeviceTest {
    @Suppress("LongMethod", "CyclomaticComplexMethod") // One owned session and unconditional restoration.
    @Test
    fun consumesBinaryResourceThroughFileReference() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("realBinaryMcp") == "true")
            val expected = requireNotNull(args.getString("binaryMarker"))
            require(expected.matches(Regex("BINARY_ONLY_[a-f0-9]{32}")))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.awaitAppContainer()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val previous = c.chatService.screen.value.openSessionId
            val session =
                c.chatService.createSession(
                    "Official SDK binary acceptance",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val binding = requireNotNull(c.storage.workspaces.binding(session))
            val root =
                c.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .resolve(binding.relativePath)
                    .toFile()
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.toPath() })
            store.ensureLayout(binding.workspaceId)
            val publisher =
                McpResourceArtifactPublisher(
                    store,
                    ToolArtifactRegistrationSink(c.storage, store::openRead) { File(root, it.relativePath) },
                    binding.workspaceId,
                ) { source, turn ->
                    c.storage.turns
                        .resolve(turn)
                        .sessionId == source
                }
            val directory = File(app.cacheDir, "real-binary-mcp/$session").apply { check(mkdirs()) }
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val original = wireField.get(factory) as WireClient
            val wire =
                LogComparisonWire(
                    (original as? BuiltInAccountWire)?.newInvocation() ?: original,
                    directory,
                    true,
                ) { "" }
            val serverId = "binary${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val endpoint = "http://127.0.0.1:19093/mcp"
            val connected = McpClients.sdk("Helix-binary-acceptance", "1").connect(endpoint)

            @Suppress("UNCHECKED_CAST")
            val bridges =
                field(
                    c.mcpService,
                    "activeBridges",
                ).get(c.mcpService) as MutableMap<String, McpDynamicToolBridge>
            val bridge =
                McpDynamicToolBridge(
                    McpServerConfig(McpServerId(serverId), NormalizedEndpoint.parse(endpoint), enabled = true),
                    connected.server,
                    emptyList(),
                    McpToolCaller { _, _ -> error("No remote tools") },
                    McpToolCaller { call, name ->
                        runBlocking {
                            McpResourceTools.execute(
                                connected,
                                name,
                                call.args,
                                serverId,
                                { blob, index -> publisher.publish(call, blob, index) },
                            )
                        }
                    },
                )
            val resourceName =
                com.helix.tools.framework.RemoteToolNames
                    .mcp(serverId, "read_mcp_resource")
                    .value
            val registry = c.toolPipeline.registry
            var availability: NaturalFixtureAvailability? = null
            var passed = false
            val origin = "http://127.0.0.1:19093"
            require(args.getString("binaryLanOrigin") == origin)
            val previousLan = c.lanScopeStore.current()
            val target =
                com.helix.core.policy
                    .NetworkOriginScope("127.0.0.1", 19093)
            val addedLan = target !in previousLan
            try {
                if (addedLan) c.lanScopeStore.add(origin)
                check(c.lanScopeStore.current() == previousLan + target)
                bridge.register(registry)
                bridges[serverId] = bridge
                val allowed =
                    setOf("tools_search", "tools_read_result", "read") +
                        McpResourceTools.names.map {
                            com.helix.tools.framework.RemoteToolNames
                                .mcp(serverId, it)
                                .value
                        }
                availability =
                    NaturalFixtureAvailability(
                        c.storage.toolAvailability,
                        registry.all().map {
                            it.origin.canonicalOf() to
                                it.name.value
                        },
                        session,
                    ).also { it.configure(allowed) }
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                wireField.set(factory, wire)
                val prompt =
                    "Read memo://owned/binary using the already exposed tool $resourceName. " +
                        "This MCP source is an owned synthetic binary file. " +
                        "Use its returned file reference with read to inspect UTF-8 contents, " +
                        "then quote the exact verification marker. " +
                        "Do not guess. Only read this resource and its returned file, do not write or execute commands."
                val receipt =
                    c.chatService
                        .sendSubmission(
                            ChatSubmission(session, 0, UUID.randomUUID().toString(), prompt),
                        ).await()
                check(receipt.outcome is ChatSubmissionOutcome.Accepted)
                withTimeout(240_000) {
                    while (true) {
                        val turns = c.storage.turns.listBySession(session)
                        check(turns.none { it.state in setOf("FAILED", "CANCELLED") }) { "Real model failed" }
                        turns
                            .flatMap {
                                c.storage.toolCalls.listByTurn(
                                    it.id,
                                )
                            }.filter { it.state == "AWAITING_APPROVAL" }
                            .forEach { call ->
                                check(call.name in allowed)
                                if (call.name ==
                                    resourceName
                                ) {
                                    check(JSONObject(call.argsJson).getString("uri") == "memo://owned/binary")
                                }
                                if (call.name ==
                                    "read"
                                ) {
                                    check(JSONObject(call.argsJson).toString().contains("output/mcp-resource-"))
                                }
                                c.chatService.approveApproval(
                                    requireNotNull(c.storage.approvals.byToolCall(call.id)).id,
                                )
                            }
                        if (turns.isNotEmpty() && turns.all { it.state == "COMPLETED" }) break
                        delay(100)
                    }
                }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                check(calls.any { it.name == resourceName && it.state == "COMPLETED" }) { "Resource read missing" }
                check(calls.any { it.name == "read" && it.state == "COMPLETED" }) { "Binary file read missing" }
                check(expected in liveResourceAnswer(c, session, turn.id)) { "Source-only marker missing" }
                val requests =
                    directory
                        .listFiles()
                        .orEmpty()
                        .filter {
                            it.name.startsWith("request-")
                        }.sortedBy {
                            it.name
                                .removePrefix("request-")
                                .removeSuffix(".json")
                                .toInt()
                        }
                check(expected !in requests.first().readText()) { "Marker leaked before source read" }
                check(requests.drop(1).any { expected in it.readText() }) { "Marker not consumed by next real request" }
                passed = true
            } finally {
                try {
                    finishOwnedCase(c.storage, c.chatService, session, {}, {
                        val turns = c.storage.turns.listBySession(session)
                        File(directory, "result.json").writeText(
                            JSONObject()
                                .put("passed", passed)
                                .put("session", session)
                                .put("server", "local official Python MCP SDK")
                                .put("modelCalls", wire.calls)
                                .put(
                                    "states",
                                    turns.map {
                                        it.state
                                    },
                                ).put(
                                    "tools",
                                    turns
                                        .flatMap {
                                            c.storage.toolCalls.listByTurn(
                                                it.id,
                                            )
                                        }.map { "${it.name}:${it.state}" },
                                ).toString(),
                        )
                    }, { stopped ->
                        if (stopped) wireField.set(factory, original)
                        availability?.restore()
                        bridges.remove(serverId)
                        registry.replaceMcpServer(serverId, emptyList())
                        c.storage.sessions.archive(session, System.currentTimeMillis())
                        previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
                    })
                } finally {
                    try {
                        connected.close()
                    } finally {
                        if (addedLan) c.lanScopeStore.remove(origin)
                        val memoryRestored = c.lanScopeStore.current() == previousLan
                        val disk =
                            com.helix.app.network
                                .LanScopeStore(
                                    com.helix.app.internal
                                        .PrefsLineStore(app, "helix-lan-scopes", synchronous = true),
                                ) { true }
                                .current()
                        val diskRestored = disk == previousLan
                        File(directory, "lan-restoration.json").writeText(
                            JSONObject()
                                .put("origin", origin)
                                .put("memoryRestored", memoryRestored)
                                .put("diskRestored", diskRestored)
                                .toString(),
                        )
                        check(memoryRestored && diskRestored) { "LAN scope restoration failed" }
                    }
                }
            }
        }

    private fun field(
        target: Any,
        name: String,
    ): java.lang.reflect.Field =
        target.javaClass.getDeclaredField(name).apply {
            isAccessible =
                true
        }
}
