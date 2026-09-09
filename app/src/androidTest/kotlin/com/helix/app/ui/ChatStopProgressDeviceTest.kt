package com.helix.app.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatStopProgressDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun stopButtonClosesTheHeldStreamAndShowsDurableStoppedState() = exercise(retry = false)

    @Test fun failedStreamWaitsForExplicitRetryAndCreatesOnlyOneNewTurn() = exercise(retry = true)

    @Test fun failedTerminalGoalDoesNotOfferAnUnavailableRetry() = exercise(retry = true, goalRetry = true)

    private fun exercise(
        retry: Boolean,
        goalRetry: Boolean = false,
    ) = runBlocking {
        compose.resetDeterministicUiState()
        prepareChatLayoutLanguage(compose)
        val container = compose.container()
        val chat = container.chatService
        val storage = container.storage
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider = createProvider(server.port)
            val session = chat.createSession("UI stop progress fixture", provider, "fixture-model-a")
            val goal =
                chat.createGoal(
                    "UI stop progress",
                    listOf("Verified output"),
                    GoalBudgets(3, 4, 100000, 60000, 30000, 0),
                )
            try {
                chat.openSession(session)
                chat.setMode(if (retry && !goalRetry) AgentMode.CHAT else AgentMode.GOAL)
                chat.setTurnBudgets(TurnBudgets(3, 4, 65536, 128, 65664))
                compose.navigateTo("sessions")
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                verifyEmptyConversation(!(retry && !goalRetry), session)
                server.holdChatStreams.set(true)
                if (retry && !goalRetry) chat.send("Reply briefly.") else chat.continueGoal(goal, "Reply briefly.")
                compose.waitUntil(10_000) { server.heldStreams.get() == 1 }
                compose.onNodeWithTag("chat-message-user").assertIsDisplayed()
                assertTrue(
                    chat.screen.value.messages
                        .any { it.role == "user" },
                )
                compose.onNodeWithTag("chat-turn-progress").assertIsDisplayed()
                compose.onNodeWithTag("chat-empty-hint").assertDoesNotExist()
                captureChatLayout(compose.activity, "running")
                when {
                    goalRetry -> verifyUnavailableGoalRetry(server, session)
                    retry -> verifyExplicitRetry(server, session)
                    else -> verifyStop(server, goal, session)
                }
            } finally {
                chat.stop()
                compose.waitUntil(10_000) { !chat.screen.value.isSending }
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
                container.privacyDeletionService.deleteGoal(goal)
                storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
                prepareChatLayoutLanguage(compose, restore = true)
            }
        }
    }

    private fun verifyEmptyConversation(
        goalMode: Boolean,
        session: String,
    ) {
        val resource = if (goalMode) R.string.chat_empty_goal else R.string.chat_empty_conversation
        verifyCompactChatSettings(compose)
        captureChatLayout(compose.activity, if (goalMode) "empty-goal" else "empty-chat")
        compose
            .onNodeWithTag(
                "chat-empty-hint",
            ).assertTextEquals(compose.activity.getString(resource))
            .assertIsDisplayed()
        if (goalMode) {
            compose
                .onNodeWithTag("chat-send")
                .assertContentDescriptionEquals(compose.activity.getString(R.string.chat_open_goals))
                .performClick()
            compose.onNodeWithTag("goal-close").assertIsDisplayed().performClick()
        } else {
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        }
        assertTrue(
            compose
                .container()
                .storage.turns
                .listBySession(session)
                .isEmpty(),
        )
    }

    private fun verifyStop(
        server: LoopbackModelServer,
        goal: String,
        session: String,
    ) {
        val storage = compose.container().storage
        val chat = compose.container().chatService
        compose.onNodeWithTag("chat-stop").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            server.heldStreamDisconnected.get() && storage.goals.resolve(goal).state == "CANCELLED" &&
                chat.screen.value.activeTurn
                    ?.state
                    ?.name == "CANCELLED"
        }
        compose
            .onNodeWithTag("chat-turn-progress")
            .assertTextEquals(compose.activity.getString(R.string.chat_cancelled))
            .assertIsDisplayed()
        compose.onNodeWithTag("chat-stop").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsDisplayed()
        assertEquals(1, server.heldStreams.get())
        assertStoppedSettlement(goal, session)
        captureChatLayout(compose.activity, "stopped")
    }

    private fun verifyExplicitRetry(
        server: LoopbackModelServer,
        session: String,
    ) {
        val storage = compose.container().storage
        val chat = compose.container().chatService
        requireNotNull(server.heldSocket.get()).close()
        compose.waitUntil(10_000) {
            chat.screen.value.activeTurn
                ?.state
                ?.name == "FAILED"
        }
        // The durable terminal precedes projection refresh and the timeline's follow-to-end effect.
        // Observe the real visible retry control before checking it; do not force-scroll it into view.
        compose.waitUntil(10_000) { compose.onNodeWithTag("chat-retry").isDisplayed() }
        compose.onNodeWithTag("chat-turn-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()
        captureChatLayout(compose.activity, "failed-chat")
        assertEquals(1, storage.turns.listBySession(session).size)
        server.holdChatStreams.set(false)
        compose.onNodeWithTag("chat-retry").performClick()
        compose.waitUntil(10_000) {
            storage.turns.listBySession(session).size == 2 && chat.screen.value.activeTurn
                ?.state
                ?.name == "COMPLETED"
        }
        val turns = storage.turns.listBySession(session)
        assertEquals(listOf("FAILED", "COMPLETED"), turns.map { it.state })
        assertTrue(
            storage.messages.listBySession(session).any { it.turnId == turns.last().id && it.role == "ASSISTANT" },
        )
        compose.onNodeWithTag("chat-retry").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsDisplayed()
    }

    private fun verifyUnavailableGoalRetry(
        server: LoopbackModelServer,
        session: String,
    ) {
        val chat = compose.container().chatService
        requireNotNull(server.heldSocket.get()).close()
        compose.waitUntil(10_000) {
            chat.screen.value.activeTurn
                ?.state
                ?.name == "FAILED"
        }
        compose.onNodeWithTag("chat-turn-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertDoesNotExist()
        compose.onNodeWithTag("goal-manage").assertIsDisplayed()
        captureChatLayout(compose.activity, "failed-goal")
        assertEquals(
            1,
            compose
                .container()
                .storage.turns
                .listBySession(session)
                .size,
        )
    }

    private fun assertStoppedSettlement(
        goal: String,
        session: String,
    ) {
        val storage = compose.container().storage
        assertEquals(
            "CANCELLED",
            storage.turns
                .listBySession(session)
                .single()
                .state,
        )
        val run = storage.goalRuns.listByGoal(goal).single()
        assertTrue(run.endedAt != null)
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
    }

    private suspend fun createProvider(port: Int): String {
        val service = compose.container().providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "UI stop progress fixture",
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
