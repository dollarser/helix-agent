package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.proot.LogComparisonWire
import com.helix.app.proot.NaturalFixtureAvailability
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mcp.McpClients
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpResourceTools
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpToolCaller
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real model and HTTP transports; only owned synthetic inventory and a public example page are sent. */
class LiveResourceModelDeviceTest {
    @Test
    fun resourceEvidenceUsesDecodedUrisAndRejectsCorruption() {
        val marker = "SYNTH_RESOURCE_0123456789abcdef"
        val raw =
            """
            {"isError":false,"structuredContent":{"untrusted":true,"resource":{
              "uri":"memo://live/source","contents":[{
                "uri":"memo://live/source",
                "text":"Owned synthetic resource. Verification marker: SYNTH_RESOURCE_0123456789abcdef",
                "contentHash":"0a33cf3a6949b0a7f31a3f0d81c1f2106015b7425fef6c3419cfafcffd6d0546"
              }]
            }}}
            """.trimIndent()
        val escaped = raw.replace("/", "\\/")
        assertTrue(verifiedLiveResourcePayload(JSONObject(raw), marker))
        assertTrue(verifiedLiveResourcePayload(JSONObject(escaped), marker))
        assertFalse(verifiedLiveResourcePayload(JSONObject(raw), "SYNTH_RESOURCE_wrong"))
        val mutations: List<(JSONObject) -> Unit> =
            listOf(
                { it.put("isError", true) },
                { it.remove("isError") },
                { it.getJSONObject("structuredContent").put("untrusted", false) },
                { it.getJSONObject("structuredContent").put("untrusted", "true") },
                { it.getJSONObject("structuredContent").remove("untrusted") },
                { it.put("structuredContent", it.getJSONObject("structuredContent").toString()) },
                { it.getJSONObject("structuredContent").getJSONObject("resource").put("uri", "memo://wrong/source") },
                { resourceContent(it).put("uri", "memo://wrong/source") },
                { resourceContent(it).put("text", "Wrong text with $marker") },
                { resourceContent(it).put("contentHash", "wrong") },
                // A raw-text hash is not the production length-framed MCP content hash.
                {
                    resourceContent(
                        it,
                    ).put("contentHash", "7ef7bb69080b0189fed9dc2ccd4404a339cb4f4be28283682d0f8ad16616bc0c")
                },
            )
        mutations.forEachIndexed { index, mutate ->
            val payload = JSONObject(escaped)
            mutate(payload)
            assertFalse("Corrupted payload $index was accepted", verifiedLiveResourcePayload(payload, marker))
        }
    }

    private fun resourceContent(payload: JSONObject): JSONObject =
        payload
            .getJSONObject("structuredContent")
            .getJSONObject("resource")
            .getJSONArray("contents")
            .getJSONObject(0)

    // Keep setup, observations and unconditional restoration in one owned lifetime.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    fun modelReadsWebAndMcpThenContinuesLongConversation() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Real model acceptance not enabled", args.getString("liveResourceAcceptance") == "true")
            val expected = requireNotNull(args.getString("liveResourceMarker"))
            require(Regex("SYNTH_RESOURCE_[a-f0-9]{16}").matches(expected))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName == "com.helix.agent.developer.asrvalidation")
            val c = app.awaitAppContainer()
            check(app.account.signedIn.value)
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            check(!c.chatService.screen.value.isDraft)
            check(c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty())
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val serverId = "livefixture$suffix"
            val endpoint = "http://127.0.0.1:19092/mcp"
            val previous = c.chatService.screen.value.openSessionId
            val session =
                c.chatService.createSession(
                    "Live resource acceptance $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val directory = File(app.cacheDir, "live-resource-model/$suffix").apply { check(mkdirs()) }
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val original = wireField.get(factory) as WireClient
            val delegate = (original as? BuiltInAccountWire)?.newInvocation() ?: original
            val wire = LogComparisonWire(delegate, directory, captureSyntheticResponses = true) { "" }

            @Suppress("UNCHECKED_CAST")
            val bridges =
                field(
                    c.mcpService,
                    "activeBridges",
                ).get(c.mcpService) as MutableMap<String, McpDynamicToolBridge>
            val registry = c.toolPipeline.registry
            var resourceSession: com.helix.extensions.mcp.McpClientSession? = null
            var availability: NaturalFixtureAvailability? = null
            var passed = false
            val lanScope = LiveResourceLanScope(app, c, directory)
            try {
                lanScope.grant(args.getString("liveResourceLanOrigin"))
                val connected = McpClients.sdk("Helix-owned-live-acceptance", "1").connect(endpoint)
                resourceSession = connected
                val bridge =
                    McpDynamicToolBridge(
                        McpServerConfig(McpServerId(serverId), NormalizedEndpoint.parse(endpoint), enabled = true),
                        connected.server,
                        emptyList(),
                        McpToolCaller { _, _ -> error("No remote tools are exposed") },
                        McpToolCaller {
                            call,
                            name,
                            ->
                            runBlocking { McpResourceTools.execute(connected, name, call.args, serverId) }
                        },
                    )
                bridge.register(registry)
                bridges[serverId] = bridge
                val allowed =
                    setOf("tools.search", "tools.read_result", "web_fetch") +
                        McpResourceTools.names.map { "mcp.$serverId.$it" }
                availability =
                    NaturalFixtureAvailability(
                        c.storage.toolAvailability,
                        registry.all().map { it.origin.canonicalOf() to it.name.value },
                        session,
                    ).also { it.configure(allowed) }
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                wireField.set(factory, wire)
                repeat(4) { checkpoint ->
                    val prompt =
                        if (checkpoint == 0) {
                            buildString {
                                append("请读取 https://example.com/ 网页正文，以及 MCP 服务 $serverId 的 memo://live/source 资源正文。")
                                append("报告网页标题和它说明的用途，再逐字引用资源里的核对标记及资源 URI。不要猜测标记。")
                                append("只允许读取这两个公开/合成来源，不修改文件，不执行命令。以下是用于本次长对话的合成库存背景。\n")
                                repeat(300) { index ->
                                    append(
                                        "Record $index: synthetic item ${index * 3 + 1}; sector ${index % 17}; " +
                                            "condition stable; no action required.\n",
                                    )
                                }
                            }
                        } else {
                            "合成库存未变，这是检查点 $checkpoint。根据刚才已读取的内容，再次简短报告网页用途、资源核对标记和 URI，不必重复读取。"
                        }
                    val receipt =
                        c.chatService
                            .sendSubmission(
                                ChatSubmission(session, 0, UUID.randomUUID().toString(), prompt),
                            ).await()
                    assertTrue(
                        "ChatService rejected owned submission",
                        receipt.outcome is ChatSubmissionOutcome.Accepted,
                    )
                    withTimeout(180_000) {
                        while (true) {
                            val turns = c.storage.turns.listBySession(session)
                            check(turns.none { it.state in setOf("FAILED", "CANCELLED") }) { "Real model turn failed" }
                            val pending =
                                turns
                                    .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                                    .filter { it.state == "AWAITING_APPROVAL" }
                            pending.forEach {
                                check(it.name in allowed) { "Refuse unexpected fixture approval" }
                                val arguments = JSONObject(it.argsJson)
                                if (it.name == "web_fetch") {
                                    check(arguments.optString("url") == "https://example.com/")
                                }
                                if (it.name.endsWith(".read_mcp_resource")) {
                                    check(arguments.optString("uri") == "memo://live/source")
                                }
                                c.chatService.approveApproval(requireNotNull(c.storage.approvals.byToolCall(it.id)).id)
                            }
                            if (turns.size == checkpoint + 1 && turns.all { it.state == "COMPLETED" }) break
                            delay(100)
                        }
                    }
                    publishLiveResourceEvidence(c, session, directory, wire, expected, false)
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    val answer = liveResourceAnswer(c, session, turn.id)
                    assertTrue("Model did not quote the source-only marker", expected in answer)
                    assertTrue("Model omitted the source URI", "memo://live/source" in answer)
                }
                val facts = publishLiveResourceEvidence(c, session, directory, wire, expected, true)
                assertTrue("Actual persisted and consumed web/resource results required", facts)
                passed = true
            } finally {
                try {
                    finishOwnedCase(c.storage, c.chatService, session, {}, {
                        publishLiveResourceEvidence(c, session, directory, wire, expected, passed)
                    }, { stopped ->
                        try {
                            if (stopped) wireField.set(factory, original)
                        } finally {
                            try {
                                availability?.restore()
                            } finally {
                                bridges.remove(serverId)
                                registry.replaceMcpServer(serverId, emptyList())
                                c.storage.sessions.archive(session, System.currentTimeMillis())
                                previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
                            }
                        }
                    })
                } finally {
                    withContext(NonCancellable) {
                        try {
                            resourceSession?.close()
                        } finally {
                            lanScope.revoke()
                        }
                    }
                }
            }
        }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
