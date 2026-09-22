package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real ChatService, safe built-in tool, vendor adapters and loopback HTTP; no external-account claim. */
class SessionInputProtocolDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun responsesPreservesToolPairingBeforeSteeringUser() =
        runBlocking {
            exercise(
                ProviderProtocol.OPENAI_RESPONSES,
            )
        }

    @Test fun anthropicPreservesToolPairingBeforeSteeringUser() =
        runBlocking { exercise(ProviderProtocol.ANTHROPIC_MESSAGES) }

    private suspend fun exercise(protocol: ProviderProtocol) {
        fixture(protocol) { session, wire ->
            val chat = compose.container().chatService
            val first =
                chat
                    .sendSubmission(submission(session, "Read the current time"))
                    .await()
                    .outcome as ChatSubmissionOutcome.Accepted
            assertTrue(wire.entered.await(10, TimeUnit.SECONDS))
            val steer =
                submission(session, SUPPLEMENT).copy(
                    delivery = SessionInputDelivery.STEER,
                    expectedTurnId = first.turnId,
                )
            assertTrue(chat.sendSubmission(steer).await().outcome is ChatSubmissionOutcome.Enqueued)
            assertEquals(1, wire.requestCount)
            wire.release.countDown()
            compose.waitUntil(20_000) { wire.requestCount >= 2 && !chat.screen.value.isSending }
            wire.assertHealthy()
            assertEquals(2, wire.requestCount)
            val storage = compose.container().storage
            assertEquals(TurnState.COMPLETED.name, storage.turns.resolve(first.turnId).state)
            assertEquals(1, storage.turns.listBySession(session).size)
            val call = storage.toolCalls.listByTurn(first.turnId).single()
            assertEquals("time.now", call.name)
            assertEquals("COMPLETED", call.state)
            assertTrue(call.callId.isNotBlank())
            assertNotNull(storage.sessionInputs.get(steer.clientRequestId)?.requestModelCallId)
            assertFalse(wire.request(0).contains(SUPPLEMENT))
            val body = Json.parseToJsonElement(wire.request(1)).jsonObject
            if (protocol == ProviderProtocol.OPENAI_RESPONSES) {
                assertResponses(body)
            } else {
                assertAnthropic(body)
            }
        }
    }

    private fun assertResponses(body: JsonObject) {
        val items = body.getValue("input").jsonArray.map { it.jsonObject }
        val call = items.single { it.string("type") == "function_call" }
        val result = items.single { it.string("type") == "function_call_output" }
        assertEquals(SessionInputProtocolStreams.CALL_ID, call.string("call_id"))
        assertEquals(call.string("call_id"), result.string("call_id"))
        assertEquals("time.now", call.string("name"))
        assertTrue(result.string("output").isNotBlank())
        val user = items.single { it.string("role") == "user" && it.toString().contains(SUPPLEMENT) }
        assertTrue(items.indexOf(call) < items.indexOf(result))
        assertTrue(items.indexOf(result) < items.indexOf(user))
        assertEquals(1, user.getValue("content").jsonArray.count { it.jsonObject.string("text") == SUPPLEMENT })
    }

    private fun assertAnthropic(body: JsonObject) {
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        val assistant =
            messages.single { message ->
                message.string("role") == "assistant" && blocks(message).any { it.string("type") == "tool_use" }
            }
        val call = blocks(assistant).single { it.string("type") == "tool_use" }
        val user = messages[messages.indexOf(assistant) + 1]
        val content = blocks(user)
        val result = content.single { it.string("type") == "tool_result" }
        val supplement = content.single { it.string("type") == "text" && it.string("text") == SUPPLEMENT }
        assertEquals("user", user.string("role"))
        assertEquals(SessionInputProtocolStreams.CALL_ID, call.string("id"))
        assertEquals("time.now", call.string("name"))
        assertEquals(call.string("id"), result.string("tool_use_id"))
        assertTrue(result.getValue("content").toString().length > 2)
        assertTrue(content.indexOf(result) < content.indexOf(supplement))
        assertEquals(1, messages.flatMap(::blocks).count { it.string("type") == "tool_result" })
        assertTrue(messages.zipWithNext().all { (a, b) -> a.string("role") != b.string("role") })
    }

    private fun blocks(message: JsonObject): List<JsonObject> =
        (message["content"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private suspend fun fixture(
        protocol: ProviderProtocol,
        block: suspend (String, SessionInputProtocolServer) -> Unit,
    ) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val previous = chat.runControl.value
        SessionInputProtocolServer(protocol).use { wire ->
            wire.start()
            val provider =
                container.providerService.create(
                    ProviderDraft(
                        null,
                        "Input protocol fixture",
                        protocol,
                        NormalizedEndpoint.parse("http://127.0.0.1:${wire.port}/v1"),
                        "fixture-model-a",
                        "{}",
                        false,
                        CleartextAuthorization("127.0.0.1", wire.port),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
            check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            check(container.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
            val session = chat.createSession("Input protocol fixture", provider, "fixture-model-a")
            try {
                wire.exercise = true
                chat.openSession(session)
                chat.setMode(AgentMode.ACT)
                block(session, wire)
            } finally {
                wire.release.countDown()
                storageStop(session)
                chat.closeSession()
                container.runControlStore.setMode(previous.mode)
                container.storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
            }
        }
    }

    private suspend fun storageStop(session: String) {
        val container = compose.container()
        container.storage.turns
            .listBySession(session)
            .forEach { container.chatService.stopTurn(it.id) }
        compose.waitUntil(10_000) { !container.chatService.screen.value.isSending }
    }

    private companion object {
        const val SUPPLEMENT = "Protocol steer: retain the completed time result"
    }
}
