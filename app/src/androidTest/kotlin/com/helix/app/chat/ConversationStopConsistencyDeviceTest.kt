package com.helix.app.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.agent.CancelResult
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationStopConsistencyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun stopTurnTransitionsThroughCancellingToTerminalCancelledInBothChatAndTasks() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                server.holdChatStreams.set(true)
                val session = chat.createSession("Stop consistency fixture", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }

                    chat.send("Test message to hold")
                    compose.waitUntil(10_000) { server.heldStreams.get() == 1 }

                    val activeTurn = chat.screen.value.activeTurn
                    assertNotNull(activeTurn)
                    val turnId = activeTurn!!.id

                    // Stop button should be displayed
                    compose.onNodeWithTag("chat-stop").assertIsDisplayed().performClick()

                    // Wait until stream disconnected and turn terminal CANCELLED
                    compose.waitUntil(10_000) {
                        server.heldStreamDisconnected.get() &&
                            chat.screen.value.activeTurn
                                ?.state == TurnState.CANCELLED
                    }

                    // Check Chat UI reflects cancelled label
                    compose
                        .onNodeWithTag("chat-turn-progress")
                        .assertTextEquals(compose.activity.getString(R.string.chat_cancelled))
                        .assertIsDisplayed()

                    // Check persistence in storage
                    val turnEntity = storage.turns.resolve(turnId)
                    assertEquals("CANCELLED", turnEntity.state)

                    // Check Tasks background task query returns cancelled
                    val bgTasks = BackgroundTaskQuery(storage).read()
                    val task = bgTasks.firstOrNull { it.id == turnId }
                    assertNotNull(task)
                    assertEquals(TurnState.CANCELLED, task!!.state)
                } finally {
                    chat.stop()
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun staleOrDuplicateStopDoesNotAffectNewTurn() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                val session = chat.createSession("Stale stop fixture", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    // First turn completes normally
                    val sub1 = ChatSubmission(session, 0L, "req-turn-1", "Turn 1")
                    val receipt1 = chat.sendSubmission(sub1).await()
                    val turn1Id = (receipt1.outcome as ChatSubmissionOutcome.Accepted).turnId

                    compose.waitUntil(10_000) {
                        storage.turns.resolve(turn1Id).state in setOf("COMPLETED", "FAILED") &&
                            chat.screen.value.activeTurn
                                ?.id == turn1Id && !chat.screen.value.isSending
                    }

                    // Hold stream for turn 2
                    server.holdChatStreams.set(true)
                    val sub2 = ChatSubmission(session, 1L, "req-turn-2", "Turn 2")
                    val receipt2 = chat.sendSubmission(sub2).await()
                    val turn2Id = (receipt2.outcome as ChatSubmissionOutcome.Accepted).turnId

                    compose.waitUntil(10_000) { server.heldStreams.get() >= 1 }

                    // Calling stop on turn 1 should return AlreadyTerminal and NOT affect turn 2
                    val cancelResult1 = chat.stopTurn(turn1Id)
                    assertTrue(cancelResult1 is CancelResult.AlreadyTerminal)

                    // Turn 2 is still running
                    val turn2State = storage.turns.resolve(turn2Id).state
                    assertEquals(TurnState.RECEIVING_MODEL.name, turn2State)

                    // A live stop acknowledges intent; cancellation settlement completes asynchronously.
                    val cancelResult2 = chat.stopTurn(turn2Id)
                    assertEquals(CancelResult.StopAccepted, cancelResult2)
                    compose.waitUntil(10_000) {
                        server.heldStreamDisconnected.get() &&
                            storage.turns.resolve(turn2Id).state == TurnState.CANCELLED.name &&
                            !chat.screen.value.isSending
                    }
                    assertEquals(CancelResult.AlreadyTerminal(TurnState.CANCELLED), chat.stopTurn(turn2Id))
                    assertEquals(2, storage.turns.listBySession(session).size)
                } finally {
                    chat.stop()
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    private suspend fun createProvider(port: Int): String {
        val service = compose.container().providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "Stop consistency fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        try {
            check(service.runConnectionTest(id) is ProbeOutcome.Ok)
        } catch (failure: Throwable) {
            service.delete(id)
            throw failure
        }
        return id
    }
}
