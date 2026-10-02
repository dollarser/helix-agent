package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Scripted provider verifies Harness backfill/recovery, not model reasoning quality. */
class EvaluationTrajectoryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun failedReadBackfillsErrorThenChangesToolWithoutRepeatingTheFailure() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port)
                check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val session = chat.createSession("Tool recovery eval", provider, "fixture-model-a")
                val requests = AtomicInteger()
                val fixtureFailure = AtomicReference<Throwable?>()
                val missing = "eval-missing-${UUID.randomUUID()}.txt"
                try {
                    server.scriptedChat = { body ->
                        verifiedResponse(requests.incrementAndGet(), body, session, missing, fixtureFailure)
                    }
                    chat.openSession(session)
                    chat.setMode(AgentMode.ACT)
                    compose.waitUntil(20_000) {
                        container.storage.sessionRunControls
                            .forSession(session)
                            ?.mode == AgentMode.ACT
                    }
                    com.helix.app.test
                        .discoverFixtureTool(container.toolPipeline, session, "time.now")
                    val submission =
                        ChatSubmission(
                            session,
                            0,
                            UUID.randomUUID().toString(),
                            "Read the fixture; if unavailable, report the current time instead.",
                            emptyList(),
                        )
                    compose.awaitAdmittedTurn(chat.sendSubmission(submission).await())
                    awaitRecovery(session, requests, fixtureFailure)
                    assertTrajectory(session, requests.get())
                } finally {
                    container.storage.turns
                        .listBySession(session)
                        .forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    container.storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(provider)
                }
            }
        }

    private fun assertTrajectory(
        session: String,
        requests: Int,
    ) {
        val storage = compose.container().storage
        val turn = storage.turns.listBySession(session).single()
        val calls = storage.toolCalls.listByTurn(turn.id)
        assertEquals("COMPLETED", turn.state)
        assertEquals(listOf("read", "time.now"), calls.map { it.name })
        assertEquals(listOf("FAILED", "COMPLETED"), calls.map { it.state })
        assertEquals(3, storage.modelCalls.listByTurn(turn.id).size)
        assertEquals(3, requests)
    }

    private fun verifiedResponse(
        index: Int,
        body: String,
        session: String,
        missing: String,
        failureRef: AtomicReference<Throwable?>,
    ): String =
        try {
            response(index, body, session, missing)
        } catch (failure: Throwable) {
            failureRef.set(failure)
            throw failure
        }

    private fun awaitRecovery(
        session: String,
        requests: AtomicInteger,
        fixtureFailure: AtomicReference<Throwable?>,
    ) {
        val container = compose.container()
        val chat = container.chatService
        try {
            compose.waitUntil(20_000) {
                fixtureFailure.get() != null || (requests.get() >= 3 && !chat.screen.value.isSending)
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "requests=${requests.get()}, turns=${container.storage.turns.listBySession(
                    session,
                )}, screen=${chat.screen.value}",
                failure,
            )
        }
        fixtureFailure.get()?.let { throw AssertionError("Scripted provider verifier failed", it) }
    }

    private suspend fun createProvider(port: Int): String =
        compose.container().providerService.create(
            ProviderDraft(
                null,
                "Trajectory fixture",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                "fixture-model-a",
                "{}",
                false,
                CleartextWarning("127.0.0.1", port),
                emptyList(),
            ),
            null,
        )

    private fun response(
        index: Int,
        body: String,
        session: String,
        missing: String,
    ): String =
        when (index) {
            1 -> {
                toolCallStream("eval-read", "\"read\"", """{"path":"$missing"}""")
            }

            2 -> {
                val messages =
                    Json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("messages")
                        .jsonArray
                val backfill =
                    messages.map { it.jsonObject }.single {
                        it["role"]?.jsonPrimitive?.content == "tool" &&
                            it["tool_call_id"]?.jsonPrimitive?.content == "eval-read"
                    }
                val outcome = backfill.getValue("content").jsonPrimitive.content
                check(outcome.startsWith("[TOOL_FAILED]"))
                check(outcome.contains(missing))
                val storage = compose.container().storage
                val turn = storage.turns.listBySession(session).single()
                val call = storage.toolCalls.listByTurn(turn.id).single()
                check(call.state == "FAILED")
                val result = requireNotNull(storage.toolResults.byToolCall(call.id))
                check(result.status == "FAILED")
                toolCallStream("eval-time", "\"time.now\"", "{}")
            }

            3 -> {
                textAnswerStream("Recovered using a different read-only tool.")
            }

            else -> {
                error("Unexpected retry in fixed trajectory")
            }
        }
}
