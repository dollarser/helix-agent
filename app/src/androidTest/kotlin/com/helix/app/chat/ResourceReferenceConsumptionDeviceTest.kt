package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.MainActivity
import com.helix.app.ToolArtifactRegistrationSink
import com.helix.app.eval.SyntheticModelSubmission
import com.helix.app.mcp.McpResourceArtifactPublisher
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.test.registeredFixtureToolName
import com.helix.app.ui.container
import com.helix.core.model.AgentMode
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.mcp.McpClientSession
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpMetadataLimits
import com.helix.extensions.mcp.McpMetadataSnapshot
import com.helix.extensions.mcp.McpResourceBlob
import com.helix.extensions.mcp.McpResourcePage
import com.helix.extensions.mcp.McpResourceTools
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpServerIdentity
import com.helix.extensions.mcp.McpToolCaller
import com.helix.extensions.mcp.McpToolResult
import com.helix.extensions.mcp.McpToolResultLimits
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Offline replies exercise production admission, tools, Room and encoding, not model understanding. */
class ResourceReferenceConsumptionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("LongMethod") // Keep owned setup, execution, evidence and cleanup in one lifetime.
    @Test
    fun binaryReferencesReachNextRequestWithoutBinaryPayload() =
        withLocalProvider { c, session, server ->
            val root = File(compose.activity.filesDir, "resource-consumption-${UUID.randomUUID()}").apply { mkdirs() }
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }).also { it.ensureLayout("fixture") }
            val sink = ToolArtifactRegistrationSink(c.storage, store::openRead) { File(root, it.relativePath) }
            val publisher =
                McpResourceArtifactPublisher(store, sink, "fixture") { source, turn ->
                    c.storage.turns
                        .resolve(turn)
                        .sessionId == source
                }
            val resource = BinarySession()
            val id = "binaryfixture${UUID.randomUUID().toString().take(8)}"
            val name =
                com.helix.tools.framework.RemoteToolNames
                    .mcp(id, "read_mcp_resource")
                    .value
            val bridge =
                McpDynamicToolBridge(
                    McpServerConfig(
                        McpServerId(id),
                        NormalizedEndpoint.parse("https://resource.invalid/mcp"),
                        enabled = true,
                    ),
                    resource.server,
                    emptyList(),
                    McpToolCaller { _, _ -> error("Not a tools/call operation") },
                    McpToolCaller { call, operation ->
                        runBlocking {
                            McpResourceTools.execute(resource, operation, call.args, id, { blob, index ->
                                publisher.publish(call, blob, index)
                            })
                        }
                    },
                )

            @Suppress("UNCHECKED_CAST")
            val bridges =
                c.mcpService.javaClass
                    .getDeclaredField("activeBridges")
                    .apply { isAccessible = true }
                    .get(c.mcpService) as MutableMap<String, McpDynamicToolBridge>
            val requests = CopyOnWriteArrayList<String>()
            val evidence = File(compose.activity.cacheDir, "binary-consumption/${UUID.randomUUID()}").apply { mkdirs() }
            var fixtureApprovals = 0
            var passed = false
            try {
                bridge.register(c.toolPipeline.registry)
                bridges[id] = bridge
                server.scriptedChat = { body ->
                    requests += body
                    when (requests.size) {
                        1 -> {
                            toolCallStream(
                                "discover",
                                JSONObject.quote(registeredFixtureToolName(body, "tools_search")),
                                """{"query":"$id"}""",
                            )
                        }

                        2 -> {
                            toolCallStream(
                                "binary-read",
                                JSONObject.quote(registeredFixtureToolName(body, name)),
                                """{"uri":"$URI"}""",
                            )
                        }

                        3 -> {
                            textAnswerStream("Binary reference received")
                        }

                        else -> {
                            error("Unexpected extra request")
                        }
                    }
                }
                val turn =
                    submitAndWait(c, session, "Read the fixture resource") { call ->
                        check(
                            c.storage.turns
                                .resolve(call.turnId)
                                .sessionId == session,
                        )
                        check(call.name == name) { "Unexpected fixture approval: ${call.name}" }
                        val args = JSONObject(call.argsJson)
                        check(args.length() == 1 && args.getString("uri") == URI)
                        check(fixtureApprovals == 0) { "Duplicate fixture approval" }
                        val approval = requireNotNull(c.storage.approvals.byToolCall(call.id))
                        check(approval.decision == null) { "Fixture approval was already decided" }
                        c.chatService.approveApproval(approval.id)
                        fixtureApprovals++
                    }
                assertEquals("Only the owned binary read is approved by this fixture", 1, fixtureApprovals)
                assertEquals(1, resource.reads)
                assertEquals(3, requests.size)
                val persisted = result(c, session, name, "binary-read")
                val content = persisted.getJSONObject("structuredContent")
                assertTrue(content.getBoolean("untrusted"))
                val row = content.getJSONObject("resource").getJSONArray("contents").getJSONObject(0)
                assertEquals(URI, row.getString("uri"))
                assertEquals(resource.hash, row.getString("contentHash"))
                val artifact = row.getJSONObject("artifact")
                assertEquals("REGISTERED", artifact.getString("status"))
                val registered = c.storage.artifacts.resolve(artifact.getString("artifactRef"))
                assertEquals(session, registered.sessionId)
                assertEquals(turn, registered.turnId)
                assertEquals(resource.hash, registered.sha256)
                assertArrayEquals(
                    resource.bytes,
                    store
                        .openRead(
                            FileScopePath.fromModelReference(artifact.getString("path")),
                        ).use {
                            it.readBytes()
                        },
                )
                val delivered = toolOutput(requests[2], "binary-read")
                listOf(URI, resource.hash, artifact.getString("artifactRef"), artifact.getString("path")).forEach {
                    assertTrue("Missing reference in subsequent provider request: $it", delivered.contains(it))
                    assertFalse("Reference appeared before tool execution", requests[0].contains(it))
                }
                val base64 =
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(resource.bytes)
                assertFalse(persisted.toString().contains(base64))
                assertFalse(requests.any { it.contains(base64) })
                assertFalse(requests.any { it.contains(resource.bytes.toString(Charsets.UTF_8)) })
                passed = true
            } finally {
                try {
                    File(evidence, "requests.json").writeText(org.json.JSONArray(requests.toList()).toString(2))
                    val rows =
                        c.storage.messages
                            .listBySession(session)
                            .filter { it.kind == "TOOL_RESULT" }
                    File(evidence, "tool-results.json").writeText(
                        org.json
                            .JSONArray(
                                rows.map {
                                    JSONObject().put("messageId", it.id).put("body", c.storage.messages.readContent(it))
                                },
                            ).toString(2),
                    )
                    File(evidence, "result.json").writeText(
                        JSONObject()
                            .put("completed", passed)
                            .put("sessionId", session)
                            .put("fixtureApprovals", fixtureApprovals)
                            .put("humanInterventions", if (passed) 0 else JSONObject.NULL)
                            .put("wireRequests", requests.size)
                            .put("resourceReads", resource.reads)
                            .toString(2),
                    )
                } finally {
                    bridges.remove(id)
                    c.toolPipeline.registry.replaceMcpServer(id, emptyList())
                    root.deleteRecursively()
                }
            }
        }

    @Suppress("LongMethod") // Preserve each original request and Room result even when an assertion fails.
    @Test
    fun grantedSessionHistoryReachesNextRequestWithoutUnrelatedHistory() =
        withLocalProvider { c, source, server ->
            withSharedHistory(c, source) { target, hidden, marker ->
                val requests = CopyOnWriteArrayList<String>()
                val evidence =
                    File(
                        compose.activity.cacheDir,
                        "session-consumption/${UUID.randomUUID()}",
                    ).apply { mkdirs() }
                var passed = false
                server.scriptedChat = { body ->
                    requests += body
                    when (requests.size) {
                        1 -> {
                            toolCallStream(
                                "discover",
                                JSONObject.quote(registeredFixtureToolName(body, "tools_search")),
                                """{"query":"session"}""",
                            )
                        }

                        2 -> {
                            toolCallStream(
                                "list",
                                JSONObject.quote(registeredFixtureToolName(body, "session_list")),
                                "{}",
                            )
                        }

                        3 -> {
                            toolCallStream(
                                "history",
                                JSONObject.quote(registeredFixtureToolName(body, "session_history")),
                                """{"targetSessionId":"$target"}""",
                            )
                        }

                        4 -> {
                            // History is an index, not message bodies. Read exactly the delivered identity/hash.
                            val output = toolOutput(body, "history")
                            check(output.startsWith("[SUCCEEDED] ")) { "History did not succeed: $output" }
                            val history =
                                JSONObject(output.removePrefix("[SUCCEEDED] ").substringBefore("\nDelivery facts:"))
                            val entries = history.getJSONArray("entries")
                            check(entries.length() == 1)
                            val entry = entries.getJSONObject(0)
                            val arguments =
                                JSONObject()
                                    .put("targetSessionId", target)
                                    .put("messageId", entry.getString("messageId"))
                                    .put("contentHash", entry.getString("contentHash"))
                                    .put("offset", 0)
                            toolCallStream(
                                "read",
                                JSONObject.quote(registeredFixtureToolName(body, "session_read")),
                                arguments.toString(),
                            )
                        }

                        5 -> {
                            textAnswerStream("Fixture completed")
                        }

                        else -> {
                            error("Unexpected extra request")
                        }
                    }
                }
                try {
                    submitAndWait(c, source, "Find the saved launch code in the shared planning conversation")
                    assertEquals(5, requests.size)
                    assertTrue(toolOutput(requests[2], "list").contains(target))
                    assertFalse(toolOutput(requests[2], "list").contains(hidden))
                    assertFalse(
                        "History must not eagerly disclose bodies",
                        toolOutput(requests[3], "history").contains(marker),
                    )
                    assertTrue(toolOutput(requests[4], "read").contains(marker))
                    assertFalse(requests[0].contains(marker))
                    assertFalse(requests.any { it.contains("PRIVATE_UNGRANTED_MARKER") })
                    val history = result(c, source, "session_history", "history")
                    assertFalse(history.toString().contains(marker))
                    val read = result(c, source, "session_read", "read")
                    assertEquals(
                        history.getJSONArray("entries").getJSONObject(0).getString("messageId"),
                        read.getString("messageId"),
                    )
                    assertTrue(read.toString().contains(marker))
                    assertTrue(
                        c.storage.turns
                            .listBySession(target)
                            .isEmpty(),
                    )
                    passed = true
                } finally {
                    // Synthetic request bodies only: no headers, credentials or unrelated sessions.
                    File(evidence, "requests.json").writeText(org.json.JSONArray(requests.toList()).toString(2))
                    val rows =
                        c.storage.messages
                            .listBySession(source)
                            .filter { it.kind == "TOOL_RESULT" }
                    File(evidence, "tool-results.json").writeText(
                        org.json
                            .JSONArray(
                                rows.map {
                                    JSONObject().put("messageId", it.id).put("body", c.storage.messages.readContent(it))
                                },
                            ).toString(2),
                    )
                    File(evidence, "result.json").writeText(
                        JSONObject()
                            .put("completed", passed)
                            .put(
                                "sessionId",
                                source,
                            ).put("targetSessionId", target)
                            .put("wireRequests", requests.size)
                            .toString(2),
                    )
                }
            }
        }

    /** Tests response persistence, not native-search request admission or a live OpenAI search. */
    @Suppress("LongMethod") // Keep owned setup, execution, evidence and cleanup in one lifetime.
    @Test
    fun nativeSearchStatusAndCitationPersistIntoTheFollowingTurn() =
        runBlocking {
            val c = compose.container()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            SessionInputProtocolServer(ProviderProtocol.OPENAI_RESPONSES).use { server ->
                server.start()
                val provider =
                    c.providerService.create(
                        ProviderDraft(
                            null,
                            "Native response fixture",
                            ProviderProtocol.OPENAI_RESPONSES,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                try {
                    check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    check(c.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
                    withSession(c, provider, "fixture-model-a") { session ->
                        server.exerciseReply = { _, index ->
                            if (index ==
                                1
                            ) {
                                nativeSearchStream()
                            } else {
                                SessionInputProtocolStreams.text(ProviderProtocol.OPENAI_RESPONSES)
                            }
                        }
                        server.exercise = true
                        val turn = submitAndWait(c, session, "Read the synthetic search answer")
                        val answer =
                            c.storage.messages
                                .listBySession(session)
                                .last {
                                    it.role == "ASSISTANT" &&
                                        it.kind == "TEXT"
                                }.let { requireNotNull(c.storage.messages.readContent(it)) }
                        assertTrue(answer.contains("OpenAI web search: started."))
                        assertTrue(answer.contains("OpenAI web search: completed."))
                        assertTrue(answer.contains("[Fixture source](https://example.com/native-source)"))
                        assertEquals(1, Regex("https://example.com/native-source").findAll(answer).count())
                        assertTrue(
                            c.storage.toolCalls
                                .listByTurn(turn)
                                .isEmpty(),
                        )
                        val admission =
                            c.chatService
                                .sendSubmission(
                                    ChatSubmission(session, 0, UUID.randomUUID().toString(), "Retain that source"),
                                ).await()
                        assertTrue(admission.outcome is ChatSubmissionOutcome.Accepted)
                        compose.waitUntil(30_000) {
                            c.storage.turns
                                .listBySession(session)
                                .size == 2 &&
                                !c.chatService.screen.value.isSending
                        }
                        assertTrue(
                            c.storage.turns
                                .listBySession(session)
                                .all { it.state == "COMPLETED" },
                        )
                        assertEquals(2, server.requestCount)
                        val request = JSONObject(server.request(1)).getJSONArray("input")
                        val prior =
                            (0 until request.length()).map { request.getJSONObject(it) }.single {
                                it.optString("role") ==
                                    "assistant"
                            }
                        assertTrue(prior.toString().contains("native-source"))
                        assertTrue(prior.toString().contains("OpenAI web search: completed."))
                        server.assertHealthy()
                    }
                } finally {
                    c.providerService.delete(provider)
                }
            }
        }

    private fun nativeSearchStream(): String {
        fun event(
            name: String,
            body: String,
        ) = "event: $name\ndata: $body\n\n"
        val citation = """{"type":"url_citation","url":"https://example.com/native-source","title":"Fixture source"}"""
        return event(
            "response.output_item.added",
            """{"output_index":0,"item":{"type":"web_search_call","status":"in_progress"}}""",
        ) +
            event(
                "response.output_item.done",
                """{"output_index":0,"item":{"type":"web_search_call","status":"completed"}}""",
            ) +
            event("response.output_text.delta", """{"delta":"Synthetic source answer."}""") +
            event("response.output_text.annotation.added", """{"annotation":$citation}""") +
            event("response.completed", """{"response":{"status":"completed","output":[]}}""")
    }

    /** Opt-in real configured provider. No scripted replies, credential reads or admission substitution. */
    @Suppress("LongMethod") // Keep owned setup, execution, evidence and cleanup in one lifetime.
    @Test
    fun crossSessionNaturalDiscoveryUsesGrantedHistory() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit live model opt-in required", args.getString("helixCrossSessionModel") != null)
            require(args.getString("helixCrossSessionModel") == "true")
            check(args.getString("helixProviderId") == null && args.getString("helixModelId") == null) {
                "Real-task baseline uses the candidate built-in model; overrides are forbidden"
            }
            val provider = com.helix.app.provider.BuiltInModel.PROVIDER_ID
            val model = com.helix.app.provider.BuiltInModel.MODEL_ID
            val c = compose.container()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "Do not disturb active owner tasks" }
            val evidence =
                JSONObject()
                    .put("completed", false)
                    .put("taskPassed", false)
                    .put("wireRequests", JSONObject.NULL)
                    .put("httpStatuses", org.json.JSONArray())
                    .put("humanInterventions", 0)
                    .put("fixtureApprovals", 0)
            val directory =
                File(
                    compose.activity.cacheDir,
                    "cross-session-model/${UUID.randomUUID()}",
                ).apply { mkdirs() }
            val started = android.os.SystemClock.elapsedRealtime()
            var fixtureApprovals = 0
            try {
                val memoryEmpty = c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty()
                evidence.put("globalMemoryEmpty", memoryEmpty)
                check(memoryEmpty) { "Global Memory must be empty; do not clear owner context" }
                withSession(c, provider, model) { source ->
                    val admission = SyntheticModelSubmission(source) { fixtureApprovals++ }
                    evidence.put("submissionReceipts", admission.receipts)
                    evidence.put("sessionId", source)
                    withSharedHistory(c, source) { target, _, marker ->
                        val turn =
                            submitAndWait(
                                c,
                                source,
                                "请找回我已共享的 Planning fixture 会话里的 launch code，" +
                                    "回答原样代码。不修改或启动其他会话。",
                                modelAdmission = admission,
                            )
                        val calls = c.storage.toolCalls.listByTurn(turn)
                        assertTrue(calls.any { it.name == "session_list" && it.state == "COMPLETED" })
                        assertTrue(
                            calls.any {
                                it.name in setOf("session_history", "session_search", "session_read") &&
                                    it.state == "COMPLETED" &&
                                    it.argsJson.contains(target)
                            },
                        )
                        val persisted = c.storage.messages.listBySession(source)
                        assertTrue(
                            persisted
                                .filter {
                                    it.kind == "TOOL_RESULT"
                                }.any {
                                    c.storage.messages
                                        .readContent(it)
                                        .orEmpty()
                                        .contains(marker)
                                },
                        )
                        assertTrue(
                            persisted
                                .last {
                                    it.role == "ASSISTANT" && it.kind == "TEXT"
                                }.let {
                                    c.storage.messages
                                        .readContent(it)
                                        .orEmpty()
                                        .contains(marker)
                                },
                        )
                        assertTrue(
                            c.storage.turns
                                .listBySession(target)
                                .isEmpty(),
                        )
                        evidence.put("completed", true).put("taskPassed", true)
                        println("CROSS_SESSION_NATURAL_PASS session=$source turn=$turn")
                    }
                }
            } finally {
                evidence.put("fixtureApprovals", fixtureApprovals)
                val source = evidence.optString("sessionId")
                val turns = c.storage.turns.listBySession(source)
                evidence.put(
                    "modelIdentities",
                    org.json.JSONArray(
                        turns.flatMap { c.storage.modelCalls.listByTurn(it.id) }.map {
                            val snapshot = JSONObject(it.providerSnapshot)
                            JSONObject()
                                .put("providerId", snapshot.getString("providerId"))
                                .put("modelId", snapshot.getString("model"))
                        },
                    ),
                )
                evidence.put(
                    "modelCalls",
                    turns.sumOf {
                        c.storage.modelCalls
                            .listByTurn(it.id)
                            .size
                    },
                )
                evidence.put(
                    "calls",
                    org.json.JSONArray(
                        turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }.map {
                            JSONObject().put("name", it.name).put("state", it.state)
                        },
                    ),
                )
                evidence.put(
                    "turns",
                    org.json.JSONArray(
                        turns.map {
                            JSONObject().put("state", it.state).put("errorCode", it.errorCode)
                        },
                    ),
                )
                evidence.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                File(directory, "result.json").writeText(evidence.toString(2))
            }
        }

    private fun withLocalProvider(block: suspend (AppContainer, String, LoopbackModelServer) -> Unit) =
        runBlocking {
            val c = compose.container()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "Do not disturb active owner tasks" }
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    c.providerService.create(
                        ProviderDraft(
                            null,
                            "Resource consumption fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                try {
                    check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    check(c.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
                    withSession(c, provider, "fixture-model-a") { block(c, it, server) }
                } finally {
                    c.providerService.delete(provider)
                }
            }
        }

    private suspend fun withSession(
        c: AppContainer,
        provider: String,
        model: String,
        block: suspend (String) -> Unit,
    ) {
        val previous = c.chatService.screen.value.openSessionId
        val session = c.chatService.createSession("Resource consumption fixture", provider, model)
        try {
            c.chatService.openSession(session)
            compose.waitUntil(10_000) { c.chatService.screen.value.openSessionId == session }
            c.chatService.setMode(AgentMode.ACT)
            compose.waitUntil(10_000) {
                c.storage.sessionRunControls
                    .forSession(session)
                    ?.mode == AgentMode.ACT
            }
            block(session)
        } finally {
            c.storage.turns
                .listBySession(session)
                .forEach { c.chatService.stopTurn(it.id) }
            compose.waitUntil(10_000) { !c.chatService.screen.value.isSending }
            c.storage.sessions.archive(session, System.currentTimeMillis())
            previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
        }
    }

    private suspend fun withSharedHistory(
        c: AppContainer,
        source: String,
        block: suspend (String, String, String) -> Unit,
    ) {
        val target = UUID.randomUUID().toString()
        val hidden = UUID.randomUUID().toString()
        c.storage.sessions.create(target, "Planning fixture", null, null, System.currentTimeMillis())
        c.storage.sessions.create(hidden, "Unshared fixture", null, null, System.currentTimeMillis())
        val marker = "LAUNCH_${UUID.randomUUID().toString().replace("-", "")}"
        c.storage.messages.append(
            UUID.randomUUID().toString(),
            target,
            null,
            "USER",
            "TEXT",
            "The launch code is $marker",
        )
        c.storage.messages.append(
            UUID.randomUUID().toString(),
            hidden,
            null,
            "USER",
            "TEXT",
            "PRIVATE_UNGRANTED_MARKER",
        )
        c.storage.sessionAccess.setGrant(source, target, true, false, false)
        try {
            block(target, hidden, marker)
        } finally {
            c.storage.sessionAccess.setGrant(source, target, false, false, false)
            listOf(target, hidden).forEach { c.storage.sessions.archive(it, System.currentTimeMillis()) }
        }
    }

    private suspend fun submitAndWait(
        c: AppContainer,
        session: String,
        prompt: String,
        modelAdmission: SyntheticModelSubmission? = null,
        approveFixture: ((ToolCallEntity) -> Unit)? = null,
    ): String {
        val request = ChatSubmission(session, 0, UUID.randomUUID().toString(), prompt)
        val admission = modelAdmission?.submit(c, request) ?: c.chatService.sendSubmission(request).await()
        assertTrue(admission.outcome.toString(), admission.outcome is ChatSubmissionOutcome.Accepted)
        val handledApprovals = mutableSetOf<String>()
        compose.waitUntil(180_000) {
            val current =
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (current != null) {
                check(current.state != TurnState.NEEDS_REVIEW.name) { "Fixture needs review: ${current.id}" }
                c.storage.toolCalls.listByTurn(current.id).filter { it.state == "AWAITING_APPROVAL" }.forEach { call ->
                    val approve = checkNotNull(approveFixture) { "Unexpected approval for ${call.name}" }
                    if (handledApprovals.add(call.id)) approve(call)
                }
            }
            current?.let { TurnState.valueOf(it.state).isTerminal } == true
        }
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        assertEquals(turn.errorCode, "COMPLETED", turn.state)
        return turn.id
    }

    private fun result(
        c: AppContainer,
        session: String,
        name: String,
        id: String,
    ): JSONObject {
        val rows =
            c.storage.messages
                .listBySession(session)
                .filter { it.kind == "TOOL_RESULT" }
                .map { JSONObject(requireNotNull(c.storage.messages.readContent(it))) }
        val row = rows.single { it.getString("tool") == name && it.getString("id") == id }
        assertEquals("SUCCEEDED", row.getString("status"))
        return JSONObject(row.getString("summary"))
    }

    private fun toolOutput(
        body: String,
        id: String,
    ): String {
        val array = JSONObject(body).getJSONArray("messages")
        val rows = (0 until array.length()).map { array.getJSONObject(it) }
        assertTrue(
            rows.any { row ->
                val calls = row.optJSONArray("tool_calls")
                (0 until (calls?.length() ?: 0)).any { calls!!.getJSONObject(it).getString("id") == id }
            },
        )
        return rows.single { it.optString("role") == "tool" && it.optString("tool_call_id") == id }.getString("content")
    }

    private class BinarySession : McpClientSession {
        override val server = McpServerIdentity("binary-fixture", "1", "2025-03-26")
        val bytes = "BINARY_SOURCE_ONLY_${UUID.randomUUID()}".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var reads = 0

        override suspend fun ping() = Unit

        override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("Not used")

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
            limits: McpToolResultLimits,
        ): McpToolResult = error("Not used")

        override suspend fun listResources(cursor: String?) =
            McpResourcePage(listOf(buildJsonObject { put("uri", URI) }), null)

        override suspend fun listResourceTemplates(cursor: String?) = McpResourcePage(emptyList(), null)

        override suspend fun readResource(uri: String): List<McpResourceBlob> {
            check(uri == URI)
            reads++
            return listOf(McpResourceBlob(uri, "application/octet-stream", bytes, hash))
        }

        override suspend fun close() = Unit
    }

    private companion object {
        const val URI = "memo://fixture/binary"
    }
}
