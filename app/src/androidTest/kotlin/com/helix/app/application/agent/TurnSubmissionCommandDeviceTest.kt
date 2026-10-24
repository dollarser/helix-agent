package com.helix.app.application.agent

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Exercises the real AppAgentRuntime → application admission → Room → Engine composition. */
class TurnSubmissionCommandDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun explicitMissingSessionNeverFallsBackToOpenConversation() =
        withRuntime { provider, session ->
            val container = compose.container()
            val unknown = command(provider, "missing-${UUID.randomUUID()}")
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(unknown) }
                }
            assertEquals(unknown.session.value, error.sessionId)
            assertEquals(TurnSubmissionBlock.SESSION_UNAVAILABLE, error.reason)
            assertTrue(
                container.storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
        }

    @Test
    fun archivedExplicitSessionCannotAdmitNewTurn() =
        withRuntime { provider, session ->
            val container = compose.container()
            container.storage.sessions.archive(session, System.currentTimeMillis())
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(command(provider, session)) }
                }
            assertEquals(TurnSubmissionBlock.SESSION_UNAVAILABLE, error.reason)
            assertTrue(
                container.storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
        }

    @Test
    fun sameReceiptDeduplicatesAndDivergentInputIsTypedConflict() =
        withRuntime { provider, session ->
            val container = compose.container()
            val input = command(provider, session)
            val original = container.agentRuntime.submit(input)
            assertEquals(original, container.agentRuntime.submit(input))
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(input.copy(text = "different input")) }
                }
            assertEquals(TurnSubmissionBlock.RECEIPT_CONFLICT, error.reason)
            assertEquals(
                listOf(original.value),
                container.storage.turns
                    .listBySession(session)
                    .map { it.id },
            )
            assertEquals(
                1,
                container.storage.modelCalls
                    .listByTurn(original.value)
                    .size,
            )
            assertEquals(
                1,
                container.storage.messages
                    .listBySession(session)
                    .count { it.role == "USER" },
            )
        }

    private fun withRuntime(block: suspend (String, String) -> Unit) =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Application admission fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                val session =
                    try {
                        check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                        chat.createSession("Application admission fixture", provider, "fixture-model-a")
                    } catch (failure: Throwable) {
                        container.providerService.delete(provider)
                        throw failure
                    }
                try {
                    server.holdChatStreams.set(true)
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                    block(provider, session)
                } finally {
                    container.storage.turns
                        .listBySession(session)
                        .forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) {
                        container.storage.turns.listBySession(session).all {
                            com.helix.core.model.TurnState
                                .valueOf(it.state)
                                .isTerminal
                        }
                    }
                    chat.closeSession()
                    if (container.storage.sessions
                            .resolve(session)
                            .archivedAt == null
                    ) {
                        container.storage.sessions.archive(session, System.currentTimeMillis())
                    }
                    container.providerService.delete(provider)
                }
            }
        }

    private fun command(
        provider: String,
        session: String,
    ) = SubmitTurnCommand(
        session = SessionId(session),
        providerId = ProviderId(provider),
        mode = AgentMode.CHAT,
        text = "Synthetic admission check",
        budgets = TurnBudgets(maxOutputTokens = 1024),
        clientRequestId = UUID.randomUUID().toString(),
    )
}
