package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ChatCompactionFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun automaticSummaryContinuesTheOriginalRequest() = exercise(manual = false)

    @Test fun manualSummaryUsesOneCallAndKeepsAllOriginalMessages() = exercise(manual = true)

    @Test fun cancelledSummaryKeepsThePreviousHistory() = exercise(manual = true, cancel = true)

    @Test fun summaryCannotBypassTheModelCallBudget() = exercise(manual = false, calls = 1)

    @Test fun goalSummarySpendsGoalBudgetAndParksWithoutAnUnrequestedWake() = exercise(manual = false, goal = true)

    @Test fun failedSummaryRetriesOnceThroughTheRealProviderAndKeepsAccounting() =
        exercise(manual = false, failSummary = true)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Shared fixture teardown for five production-path outcomes.
    private fun exercise(
        manual: Boolean,
        cancel: Boolean = false,
        calls: Int = 4,
        goal: Boolean = false,
        failSummary: Boolean = false,
    ) = runBlocking {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val storage = container.storage
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val service = container.providerService
            val provider =
                service.create(
                    ProviderDraft(
                        null,
                        "Compaction flow fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                        "fixture-model-a",
                        "{}",
                        false,
                        CleartextAuthorization("127.0.0.1", server.port),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
            check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
            val session = chat.createSession("Compaction flow fixture", provider, "fixture-model-a")
            try {
                service.saveContextSettings(
                    provider,
                    "fixture-model-a",
                    ProviderContextSettings(
                        manualWindow = if (goal) 65536 else 8192,
                        autoCompact = !manual,
                        triggerPercent = if (goal) 10 else 30,
                    ),
                )
                seed(session)
                val originals =
                    storage.messages.listBySession(session).associate {
                        it.id to storage.messages.readContent(it)
                    }
                chat.openSession(session)
                chat.setMode(if (goal) AgentMode.GOAL else AgentMode.CHAT)
                chat.setTurnBudgets(TurnBudgets(4, calls, 65536, 4096, 100000))
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                server.holdChatStreams.set(cancel)
                server.forceTextResponses = goal
                server.failNextSummary.set(failSummary)
                val goalId =
                    if (goal) {
                        chat.createGoal(
                            "Keep constraints and verify",
                            listOf("Verified result"),
                            com.helix.core.model
                                .GoalBudgets(20, 20, 200000, 600000, 300000, 1),
                        )
                    } else {
                        null
                    }
                if (manual) {
                    compose.navigateTo("sessions")
                    compose.onNodeWithTag("chat-context-window").performScrollTo().performClick()
                    compose.onNodeWithTag("context-compact-now").performClick()
                } else {
                    if (goalId != null) {
                        chat.continueGoal(goalId, "Continue with the current request.")
                    } else {
                        chat.send("Continue with the current request.")
                    }
                }
                compose.waitUntil(15_000) { storage.turns.listBySession(session).size == 4 }
                if (cancel) {
                    compose.waitUntil(15_000) { server.heldStreams.get() == 1 }
                    chat.stop()
                }
                compose.waitUntil(20_000) {
                    !chat.screen.value.isSending &&
                        storage.turns
                            .listBySession(session)
                            .last()
                            .state in setOf("COMPLETED", "FAILED", "CANCELLED")
                }
                val turn = storage.turns.listBySession(session).last()
                if (failSummary) {
                    val modelCalls = storage.modelCalls.listByTurn(turn.id)
                    assertEquals("error=${turn.errorCode}", 3, modelCalls.size)
                    assertEquals(1, modelCalls.count { it.state == "FAILED" })
                    assertTrue(!server.failNextSummary.get())
                }
                if (goalId != null) {
                    assertEquals("PAUSED", storage.goals.resolve(goalId).state)
                    assertTrue(
                        "Goal calls=${storage.goals.resolve(goalId).modelCalls}, error=${turn.errorCode}",
                        storage.goals.resolve(goalId).modelCalls >= 2,
                    )
                    assertEquals(1, storage.goalRuns.listByGoal(goalId).size)
                }
                val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession(session))
                val expected =
                    if (cancel) {
                        "CANCELLED"
                    } else if (calls == 1) {
                        "FAILED"
                    } else {
                        "COMPLETED"
                    }
                assertEquals(turn.errorCode, expected, turn.state)
                if (cancel) assertNull(checkpoint) else assertNotNull(checkpoint)
                originals.forEach { (id, content) ->
                    assertEquals(content, storage.messages.readContent(storage.messages.resolve(id)))
                }
                if (!cancel && calls > 1) {
                    assertEquals(
                        if (manual) {
                            1
                        } else if (failSummary) {
                            3
                        } else {
                            2
                        },
                        storage.modelCalls.listByTurn(turn.id).size,
                    )
                    if (manual) {
                        assertTrue(chat.screen.value.contextUsage.estimatedAfterCompaction)
                    } else {
                        assertTrue(server.lastChatRequest.get()!!.contains("UNTRUSTED_HISTORY_SUMMARY"))
                        assertTrue(server.lastChatRequest.get()!!.contains("Continue with the current request."))
                    }
                }
                if (calls == 1) assertEquals("MODEL_CALL_LIMIT", turn.errorCode)
            } finally {
                chat.stop()
                compose.waitUntil(10_000) { !chat.screen.value.isSending }
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
                storage.sessions.archive(session, System.currentTimeMillis())
                service.delete(provider)
            }
        }
    }

    private fun seed(session: String) {
        val storage = compose.container().storage
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.now()
            }
        repeat(3) { index ->
            val next = { UUID.randomUUID().toString() }
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    clock,
                    next,
                    TurnStartSpec(session, next(), next(), "{}", "constraint-$index " + "context ".repeat(600)),
                )
            val stream = coordinator.beginModelStream()
            stream.apply(ModelEvent.TextDelta("answer-$index"))
            stream.apply(ModelEvent.Completed("stop"))
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        }
    }
}
