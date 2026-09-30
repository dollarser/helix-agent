package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.chat.SessionModelSelectionResult
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Actual ChatService/AgentLoop/wire journey; only synthetic replies and read-only catalog discovery. */
class ProviderTurnJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One complete user journey with bounded waits and owned-fixture cleanup.
    fun selectedModelSurvivesToolBackfillCompactionAndContinuation() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val service = container.providerService
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    service.create(
                        ProviderDraft(
                            templateId = null,
                            displayName = "Provider chain fixture",
                            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            endpoint = NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            model = "fixture-model-a",
                            headersJson = "{}",
                            credentialRequired = false,
                            cleartext = CleartextAuthorization("127.0.0.1", server.port),
                            templateNotes = emptyList(),
                        ),
                        null,
                        cleartextConfirmed = true,
                    )
                var session: String? = null
                try {
                    check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                    check(service.runCapabilityTest(provider, MODEL) is ProbeOutcome.Ok)
                    service.saveSelectedModels(provider, listOf(MODEL))
                    service.saveContextSettings(
                        provider,
                        MODEL,
                        ProviderContextSettings(manualWindow = 32768, autoCompact = false),
                    )
                    val id = chat.createSession("Provider chain fixture", provider, "fixture-model-a")
                    session = id
                    chat.openSession(id)
                    compose.waitUntil(10000) { chat.screen.value.openSessionId == id }
                    chat.setMode(AgentMode.ACT)
                    assertEquals(
                        SessionModelSelectionResult.APPLIED,
                        chat.requestSessionModelSelection(id, provider, MODEL).await(),
                    )
                    val requests = CopyOnWriteArrayList<JsonObject>()
                    val first = AtomicBoolean(true)
                    server.scriptedChat = { body ->
                        val request = Json.parseToJsonElement(body).jsonObject
                        requests += request
                        if (first.compareAndSet(true, false)) {
                            val functions =
                                request.getValue("tools").jsonArray.map { tool ->
                                    tool.jsonObject.getValue("function").jsonObject
                                }
                            val search =
                                functions.first {
                                    it
                                        .getValue("description")
                                        .jsonPrimitive.content
                                        .startsWith("Search user-enabled tools")
                                }
                            toolCallStream(
                                "chain-search",
                                search.getValue("name").toString(),
                                """{"query":"read","limit":1}""",
                            )
                        } else {
                            val detail =
                                if (requests.size == 2) {
                                    "Verified catalog observation; no user files were changed. ".repeat(300)
                                } else {
                                    ""
                                }
                            textAnswerStream("Retained constraint: MODEL-B-ONLY. $detail")
                        }
                    }
                    chat.sendTestMessage("Discover a read tool without executing it.")
                    awaitTurns(id, 1)
                    assertTrue(requests.size >= 2)
                    assertTrue(
                        requests.any { request ->
                            request.getValue("messages").jsonArray.any {
                                it.jsonObject
                                    .getValue("role")
                                    .jsonPrimitive.content == "tool"
                            }
                        },
                    )
                    val firstTurn = storage.turns.listBySession(id).single()
                    assertTrue(
                        storage.toolCalls.listByTurn(firstTurn.id).any {
                            it.name == "tools.search" && it.state == "COMPLETED"
                        },
                    )
                    // Preserve the newest turn while making the older history eligible for compaction.
                    chat.sendTestMessage("Keep MODEL-B-ONLY for the next step.")
                    awaitTurns(id, 2)
                    chat.compactContext()
                    awaitTurns(id, 3)
                    assertTrue(
                        storage.turns.listBySession(id).joinToString { "${it.state}:${it.errorCode}" },
                        storage.messages.listBySession(id).any {
                            it.kind == com.helix.app.agent.ContextCompaction.KIND
                        },
                    )
                    chat.sendTestMessage("Continue from the retained constraint.")
                    awaitTurns(id, 4)
                    assertTrue(requests.size >= 5)
                    assertTrue(requests.all { it.getValue("model").jsonPrimitive.content == MODEL })
                    val completedTurns = storage.turns.listBySession(id)
                    assertTrue(
                        completedTurns.joinToString { "${it.id}:${it.state}:${it.errorCode}" },
                        completedTurns.all { it.state == "COMPLETED" },
                    )
                    assertEquals("fixture-model-a", storage.providerConfigs.resolve(provider).model)
                    assertEquals(MODEL, storage.sessions.resolve(id).modelId)
                } finally {
                    chat.stop()
                    compose.waitUntil(10000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    session?.let { storage.sessions.archive(it, System.currentTimeMillis()) }
                    service.delete(provider)
                }
            }
        }

    private fun awaitTurns(
        session: String,
        count: Int,
    ) {
        compose.waitUntil(20000) {
            val turns =
                compose
                    .container()
                    .storage.turns
                    .listBySession(session)
            turns.size == count && turns.all { it.state in setOf("COMPLETED", "FAILED", "CANCELLED") } &&
                !compose
                    .container()
                    .chatService.screen.value.isSending
        }
    }

    private companion object {
        const val MODEL = "fixture-model-b"
    }
}
