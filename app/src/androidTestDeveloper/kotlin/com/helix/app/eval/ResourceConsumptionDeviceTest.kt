// Exercise the existing module-internal web transport seam only.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.helix.app.eval

import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.SseBody
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.app.provider.BuiltInModel
import com.helix.app.ui.container
import com.helix.core.model.AgentMode
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolCallState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mcp.McpClientSession
import com.helix.extensions.mcp.McpDynamicToolBridge
import com.helix.extensions.mcp.McpMetadataLimits
import com.helix.extensions.mcp.McpMetadataSnapshot
import com.helix.extensions.mcp.McpResourcePage
import com.helix.extensions.mcp.McpResourceText
import com.helix.extensions.mcp.McpResourceTools
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.McpServerIdentity
import com.helix.extensions.mcp.McpToolCaller
import com.helix.extensions.mcp.McpToolResult
import com.helix.extensions.mcp.McpToolResultLimits
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.tools.android.WebFetchPage
import com.helix.tools.android.WebFetchTool
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.bindingOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic OFFLINE transport, not real-model understanding or real HTTP/MCP-server evidence.
 * Existing authorized account admission and a validated built-in provider remain prerequisites.
 * This fixture neither signs in nor substitutes account/provider validation status.
 * Real ChatService, Dispatcher, executors, Room and provider encoder carry the source into the next request.
 */
class ResourceConsumptionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("LongMethod") // Keep injection, actual turn, evidence and restoration in one bounded lifetime.
    @Test
    fun actualResourceResultsReachTheNextProviderRequest() =
        runBlocking {
            val app = compose.activity.application as HelixApplication
            check(app.packageName == "com.helix.agent.developer.asrvalidation") { "Isolated package required" }
            app.runtimeRecoveryReady.await()
            val c = compose.container()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val serverId = "resourcefixture$suffix"
            val registry = c.toolPipeline.registry
            val originalBindings =
                registry
                    .snapshot()
                    .filter {
                        it.descriptor.origin == ToolOrigin.BuiltInOrigin
                    }.map { it.binding }
            val resourceSession = ResourceSession()
            val bridge =
                McpDynamicToolBridge(
                    McpServerConfig(
                        McpServerId(serverId),
                        NormalizedEndpoint.parse("https://resource.invalid/mcp"),
                        enabled = true,
                    ),
                    resourceSession.server,
                    emptyList(),
                    McpToolCaller { _, _ -> error("Resources must not call tools/call") },
                    McpToolCaller { call, name ->
                        runBlocking { McpResourceTools.execute(resourceSession, name, call.args, serverId) }
                    },
                )

            @Suppress("UNCHECKED_CAST")
            val bridges =
                field(
                    c.mcpService,
                    "activeBridges",
                ).get(c.mcpService) as MutableMap<String, McpDynamicToolBridge>
            val previous = c.chatService.screen.value.openSessionId
            val factory = requireNotNull(field(c.providerService, "factory").get(c.providerService))
            val wireField = field(factory, "builtInWire")
            val originalWire = wireField.get(factory)
            val requests = CopyOnWriteArrayList<String>()
            val wireStages = CopyOnWriteArrayList<String>()
            val webCalls = CopyOnWriteArrayList<String>()
            val readName = "mcp.$serverId.read_mcp_resource"
            val session =
                c.chatService.createSession(
                    "Synthetic resource consumption $suffix",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            var closed = false
            try {
                registry.replaceOwner(
                    bindingOwner(ToolOrigin.BuiltInOrigin),
                    originalBindings.map {
                        if (it.descriptor.name.value != WebFetchTool.NAME) {
                            it
                        } else {
                            ToolBinding(
                                WebFetchTool.descriptor(),
                                WebFetchTool.executor { url, _, _ ->
                                    webCalls += url
                                    WebFetchPage(
                                        WEB_FINAL,
                                        200,
                                        "text/html",
                                        "UTF-8",
                                        "<main>$WEB_TEXT</main>".toByteArray(),
                                        false,
                                        1,
                                    )
                                },
                            )
                        }
                    },
                )
                bridge.register(registry)
                bridges[serverId] = bridge
                wireField.set(
                    factory,
                    object : WireClient {
                        override suspend fun open(request: WireRequest): WireResponse {
                            check(!closed) { "RESOURCE_FIXTURE_WIRE_CLOSED" }
                            if (request.method == "GET" && request.url == BuiltInModel.CATALOG_URL &&
                                request.body == null
                            ) {
                                wireStages += "catalog"
                                return WireResponse(
                                    200,
                                    mapOf("content-type" to listOf("application/json")),
                                    SseBody(
                                        (
                                            """{"data":[{"id":"${BuiltInModel.MODEL_ID}","context_length":262144,""" +
                                                """"architecture":{"output_modalities":["text"]}}]}"""
                                        ).toByteArray(),
                                    ),
                                )
                            }
                            val body = request.body?.toString(Charsets.UTF_8).orEmpty()
                            val generation = request.method == "POST" && request.url == BuiltInModel.GENERATION_URL
                            wireStages += "generation=$generation;owned=${suffix in body}"
                            check(generation && suffix in body) { "RESOURCE_FIXTURE_UNEXPECTED_REQUEST" }
                            requests += body
                            val stream =
                                when (requests.size) {
                                    1 -> {
                                        toolCallStream(
                                            "wire-discover-web",
                                            JSONObject.quote("tools.search"),
                                            """{"query":"${WebFetchTool.NAME}"}""",
                                        )
                                    }

                                    2 -> {
                                        requireExposed(body, WebFetchTool.NAME)
                                        toolCallStream(
                                            "wire-web",
                                            JSONObject.quote(WebFetchTool.NAME),
                                            """{"url":"$WEB_REQUEST#ignored"}""",
                                        )
                                    }

                                    3 -> {
                                        toolCallStream(
                                            "wire-discover-resource",
                                            JSONObject.quote("tools.search"),
                                            """{"query":"$serverId"}""",
                                        )
                                    }

                                    4 -> {
                                        requireExposed(body, readName)
                                        toolCallStream(
                                            "wire-resource",
                                            JSONObject.quote(readName),
                                            """{"uri":"$RESOURCE_URI"}""",
                                        )
                                    }

                                    5 -> {
                                        textAnswerStream("SYNTHETIC_RESOURCE_END")
                                    }

                                    else -> {
                                        error("Unexpected extra model request")
                                    }
                                }
                            return sseResponse(stream)
                        }
                    },
                )
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
                val receipt =
                    c.chatService
                        .sendSubmission(
                            ChatSubmission(
                                session,
                                0,
                                UUID.randomUUID().toString(),
                                "Synthetic resource fixture $suffix",
                            ),
                        ).await()
                assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
                withTimeout(60_000) {
                    while (c.storage.turns
                            .listBySession(session)
                            .none { it.state == "COMPLETED" }
                    ) {
                        val turns = c.storage.turns.listBySession(session)
                        check(turns.none { it.state in setOf("FAILED", "CANCELLED") }) {
                            val calls =
                                turns
                                    .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                                    .map { "${it.name}:${it.state}" }
                            "Fixture turn failed: $turns; stages=$wireStages; calls=$calls; " +
                                "webExecutions=${webCalls.size}; resourceReads=${resourceSession.reads.size}"
                        }
                        turns
                            .flatMap {
                                c.storage.toolCalls.listByTurn(
                                    it.id,
                                )
                            }.filter { it.state == "AWAITING_APPROVAL" }
                            .forEach {
                                c.chatService.approveApproval(requireNotNull(c.storage.approvals.byToolCall(it.id)).id)
                            }
                        delay(30)
                    }
                }
                val completedCalls =
                    c.storage.turns
                        .listBySession(session)
                        .flatMap { c.storage.toolCalls.listByTurn(it.id) }
                val diagnostics =
                    completedCalls.map { call ->
                        val result = c.storage.toolResults.byToolCall(call.id)
                        // Summaries are bounded to this wholly synthetic session, never provider headers/configuration.
                        "${call.name}:${call.state}; result=${result?.status}; summary=${result?.summary}"
                    }
                evidence("RESOURCE_FIXTURE_TERMINAL: stages=$wireStages; calls=$diagnostics")
                assertEquals(5, requests.size)
                assertEquals(listOf(WEB_REQUEST), webCalls.toList())
                assertEquals(listOf(RESOURCE_URI), resourceSession.reads)
                val web = toolMessage(requests[2], "wire-web")
                assertTrue(web.contains(WEB_TEXT))
                assertTrue(web.contains(WEB_FINAL))
                assertTrue(web.contains(sha(WEB_TEXT)))
                val resource = toolMessage(requests[4], "wire-resource")
                assertTrue(resource.contains(RESOURCE_TEXT))
                assertTrue(resource.contains(RESOURCE_URI))
                assertTrue(resource.contains(sha(RESOURCE_TEXT)))
                assertTrue(resource.contains("\"untrusted\":true"))
                assertFalse(requests[0].contains(WEB_TEXT))
                assertFalse(requests[0].contains(RESOURCE_TEXT))
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .single()
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                assertEquals(4, calls.size)
                assertTrue(calls.all { it.state == ToolCallState.COMPLETED.name })
                val persisted =
                    c.storage.messages
                        .listBySession(session)
                        .filter { it.kind == "TOOL_RESULT" }
                        .map {
                            c.storage.messages
                                .readContent(it)
                                .orEmpty()
                        }
                val webPayload = persistedPayload(persisted, WebFetchTool.NAME, "wire-web")
                assertEquals(WEB_TEXT, webPayload.getString("text"))
                assertEquals(WEB_REQUEST, webPayload.getString("requestedUrl"))
                assertEquals(WEB_FINAL, webPayload.getString("finalUrl"))
                assertEquals(sha(WEB_TEXT), webPayload.getString("contentHash"))
                val resourcePayload =
                    persistedPayload(persisted, readName, "wire-resource")
                        .getJSONObject("structuredContent")
                assertTrue(resourcePayload.getBoolean("untrusted"))
                val resourceData = resourcePayload.getJSONObject("resource")
                assertEquals(RESOURCE_URI, resourceData.getString("uri"))
                val contents = resourceData.getJSONArray("contents")
                assertEquals(1, contents.length())
                val content = contents.getJSONObject(0)
                assertEquals(RESOURCE_URI, content.getString("uri"))
                assertEquals(RESOURCE_TEXT, content.getString("text"))
                assertEquals(sha(RESOURCE_TEXT), content.getString("contentHash"))
                evidence(
                    "RESOURCE_CONSUMPTION_OFFLINE: requests=5; real_dispatch_room_encoder=true; " +
                        "web_and_mcp_original_wire_ids=true",
                )
            } finally {
                finishOwnedCase(c.storage, c.chatService, session, { closed = true }, {}, { stopped ->
                    if (stopped) wireField.set(factory, originalWire)
                    bridges.remove(serverId)
                    registry.replaceMcpServer(serverId, emptyList())
                    registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), originalBindings)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    previous?.let(c.chatService::openSession) ?: c.chatService.closeSession()
                })
            }
        }

    private fun persistedPayload(
        rows: List<String>,
        tool: String,
        wireId: String,
    ): JSONObject {
        val envelope = rows.map(::JSONObject).single { it.getString("tool") == tool }
        assertEquals(wireId, envelope.getString("id"))
        assertEquals("SUCCEEDED", envelope.getString("status"))
        return JSONObject(envelope.getString("summary"))
    }

    private fun evidence(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    }

    private fun requireExposed(
        body: String,
        name: String,
    ) {
        val tools = JSONObject(body).getJSONArray("tools")
        val names =
            (0 until tools.length()).map {
                tools.getJSONObject(it).getJSONObject("function").getString("name")
            }
        check(name in names) { "RESOURCE_FIXTURE_TOOL_NOT_EXPOSED: $name" }
    }

    private fun toolMessage(
        body: String,
        id: String,
    ): String {
        val messages = JSONObject(body).getJSONArray("messages")
        val rows = (0 until messages.length()).map { messages.getJSONObject(it) }
        val call =
            rows.flatMap { row ->
                val calls = row.optJSONArray("tool_calls")
                (0 until (calls?.length() ?: 0)).map { calls!!.getJSONObject(it).getString("id") }
            }
        assertTrue("Original provider call missing", id in call)
        return rows.single { it.optString("role") == "tool" && it.optString("tool_call_id") == id }.getString("content")
    }

    private fun field(
        owner: Any,
        name: String,
    ) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private class ResourceSession : McpClientSession {
        override val server = McpServerIdentity("offline-resources", "1", "2025-03-26")
        val reads = mutableListOf<String>()

        override suspend fun ping() = Unit

        override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("Not used")

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
            limits: McpToolResultLimits,
        ): McpToolResult = error("Must not call tools/call")

        override suspend fun listResources(cursor: String?) =
            McpResourcePage(
                listOf(
                    buildJsonObject {
                        put("uri", RESOURCE_URI)
                    },
                ),
                null,
            )

        override suspend fun listResourceTemplates(cursor: String?) = McpResourcePage(emptyList(), null)

        override suspend fun readResource(uri: String): List<McpResourceText> {
            check(uri == RESOURCE_URI)
            reads += uri
            return listOf(McpResourceText(uri, "text/plain", RESOURCE_TEXT, sha(RESOURCE_TEXT)))
        }

        override suspend fun close() = Unit
    }

    private companion object {
        const val WEB_REQUEST = "https://example.com/source?q=fixture"
        const val WEB_FINAL = "https://example.com/final"
        const val WEB_TEXT = "WEB_SOURCE_ONLY_7129"
        const val RESOURCE_URI = "memo://fixture/source"
        const val RESOURCE_TEXT = "MCP_SOURCE_ONLY_3918"

        fun sha(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
    }
}
