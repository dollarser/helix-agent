package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production ChatService/OkHttp/decoder admission and cancellation against a held local SSE socket. */
@RunWith(AndroidJUnit4::class)
class GoalModelCancellationDeviceTest {
    @Test fun explicitStopClosesModelSocketAndGoalRun() = exercise(stop = true)

    @Test fun wakeTimeLimitClosesModelSocketAndBlocksGoal() = exercise(stop = false)

    @Test fun rotationKeepsTheOriginalRunningGoalAndModelSocket() = exercise(stop = true, rotate = true)

    @Test fun disconnectedModelStreamFailsOnceWithoutReplayingTheGoal() = exercise(stop = false, disconnect = true)

    private fun exercise(
        stop: Boolean,
        rotate: Boolean = false,
        disconnect: Boolean = false,
    ) = runBlocking {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val chat = container.chatService
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider = createProvider(container, server.port)
            val session = chat.createSession("Goal cancellation fixture", provider, "fixture-model-a")
            val goal =
                chat.createGoal(
                    "Bound model cancellation",
                    listOf("Verified output"),
                    GoalBudgets(3, 4, 100000, 60000, if (stop || disconnect) 30000 else 2000, 0),
                )
            try {
                chat.openSession(session)
                await { chat.screen.value.openSessionId == session }
                chat.setMode(AgentMode.GOAL)
                chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 128, 10000))
                server.holdChatStreams.set(true)
                chat.continueGoal(goal, "Reply with a short plain text result.")
                await { server.heldStreams.get() == 1 }
                if (rotate) verifyGoalRotation(container, goal, session, server)
                if (disconnect) requireNotNull(server.heldSocket.get()).close()
                if (stop) chat.stop()
                await {
                    container.storage.turns.listBySession(session).singleOrNull()?.let {
                        TurnState.valueOf(it.state).isTerminal
                    } == true
                }
                if (disconnect) {
                    verifyDisconnectedGoal(container.storage, goal, session)
                } else {
                    await { server.heldStreamDisconnected.get() }
                    verifySettlement(container.storage, goal, session, stop)
                }
                assertEquals(1, server.heldStreams.get())
            } finally {
                chat.stop()
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
                container.providerService.delete(provider)
                val stored = container.storage.goals.find(goal)
                if (stored != null && stored.state != "RUNNING") container.privacyDeletionService.deleteGoal(goal)
                container.storage.sessions.archive(session, System.currentTimeMillis())
            }
        }
    }

    private suspend fun createProvider(
        container: com.helix.app.AppContainer,
        port: Int,
    ): String {
        container.storage.providerConfigs
            .list()
            .filter {
                it.displayName == "Goal cancellation fixture" && it.model == "fixture-model-a" &&
                    it.endpoint.startsWith("http://127.0.0.1:") &&
                    it.secretAlias == com.helix.app.provider.ProviderFactory.NO_KEY_ALIAS
            }.forEach { container.providerService.delete(it.id) }
        val id =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Goal cancellation fixture",
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
        var passed = false
        try {
            passed = container.providerService.runConnectionTest(id) is ProbeOutcome.Ok
            assertTrue("Fixture provider connection probe failed", passed)
        } finally {
            if (!passed) container.providerService.delete(id)
        }
        return id
    }

    private fun verifySettlement(
        storage: com.helix.core.storage.HelixStorage,
        goal: String,
        session: String,
        stop: Boolean,
    ) {
        val stored = storage.goals.resolve(goal)
        val run = storage.goalRuns.listByGoal(goal).single()
        assertEquals(if (stop) "CANCELLED" else "BLOCKED", stored.state)
        assertTrue(run.endedAt != null)
        if (!stop) assertTrue(requireNotNull(run.outcome).startsWith("BUDGET_EXHAUSTED("))
        assertEquals(1, stored.modelCalls)
        assertTrue(stored.runTimeMillis > 0)
        assertEquals(0L, stored.currentWakeMillis)
        assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
        assertTrue(
            storage.toolCalls
                .listByTurn(
                    storage.turns
                        .listBySession(session)
                        .single()
                        .id,
                ).isEmpty(),
        )
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!predicate()) {
            assertTrue(
                "Goal model cancellation boundary timed out",
                android.os.SystemClock.elapsedRealtime() < deadline,
            )
            Thread.sleep(25)
        }
    }
}
