package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.foreground.DataSyncForegroundController
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.agent.CancelResult
import com.helix.core.agent.Goal
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ModelEvent
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.GoalControlEntity
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real service + Room + loopback transport; the fixture only gates the three HTTP responses. */
class GoalInputSchedulingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val goalBudget = GoalBudgets(6, 12, 300_000, 120_000, 60_000, 0)

    @Test fun queueDoesNotInterruptGoalAndRunsBeforeTheOriginalGoalContinues() =
        runBlocking {
            fixture { chat, session, wire ->
                val storage = compose.container().storage
                val first = accepted(chat, submission(session, "Finish the original fixture objective"))
                wire.awaitRequest(0)
                val firstBinding = requireNotNull(storage.goalTurnBindings.byTurn(first))
                val goal = storage.goalRuns.resolve(firstBinding.runId).goalId
                val queued =
                    submission(
                        session,
                        "Perform this separate user request " + "x".repeat(Goal.MAX_OBJECTIVE_LENGTH),
                    )
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertNull(storage.turns.resolve(first).pauseRequestedAt)
                assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve(first).state)
                assertEquals(1, wire.count())
                assertEquals(1, storage.goalRuns.listByGoal(goal).size)

                wire.release(0)
                wire.awaitRequest(1)
                val userInput = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                val userTurn = requireNotNull(userInput.consumedTurnId)
                assertEquals(TurnState.COMPLETED.name, storage.turns.resolve(first).state)
                assertNull(storage.turns.resolve(first).pauseRequestedAt)
                assertNull(storage.goalTurnBindings.byTurn(userTurn))
                assertEquals(AgentMode.ACT.name, userInput.configuration.mode)
                assertNotNull(userInput.requestModelCallId)
                assertEquals(1, storage.goalRuns.listByGoal(goal).size)
                assertEquals("PAUSED", storage.goals.resolve(goal).state)
                val spentBeforeResume = storage.goals.resolve(goal).modelCalls
                assertTrue(spentBeforeResume > 0)
                assertTrue(wire.body(1).contains("Perform this separate user request"))
                assertFalse(wire.body(1).contains("[Goal continuation]"))
                assertEquals(CancelResult.AlreadyTerminal(TurnState.COMPLETED), chat.stopTurn(first))
                assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve(userTurn).state)
                assertEquals(userInput, storage.sessionInputs.get(queued.clientRequestId))
                assertEquals(2, wire.count())

                wire.release(1)
                wire.awaitRequest(2)
                val runs = storage.goalRuns.listByGoal(goal)
                assertEquals(2, runs.size)
                assertInheritedGoalBudget(goal, spentBeforeResume)
                assertEquals(TurnState.COMPLETED.name, storage.turns.resolve(userTurn).state)
                val resumedTurn =
                    storage.turns.listBySession(session).single {
                        it.id != first && it.id != userTurn
                    }
                val resumedBinding = requireNotNull(storage.goalTurnBindings.byTurn(resumedTurn.id))
                assertEquals(goal, storage.goalRuns.resolve(resumedBinding.runId).goalId)
                assertTrue(wire.body(2).contains("[Goal continuation]"))
                assertEquals(1, GoalSummaryQuery(storage).forSession(session).size)

                chat.stopTurn(resumedTurn.id)
                wire.release(2)
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                assertEquals(TurnState.CANCELLED.name, storage.turns.resolve(resumedTurn.id).state)
                assertFalse(wire.unexpected.await(500, TimeUnit.MILLISECONDS))
                assertEquals(3, wire.count())
            }
        }

    private fun configureGoalBudget(session: String) {
        val container = compose.container()
        compose.waitUntil(15_000) {
            container.storage.sessionRunControls.forSession(session)?.let {
                it.mode == AgentMode.GOAL && it.budgets == TurnBudgets(4, 6, 64_000, 128, 100_000)
            } == true
        }
        val controls =
            com.helix.app.runcontrol.SessionRunControlStore(
                container.storage,
                container.runControlStore,
            )
        controls.set(
            session,
            controls.ensure(session, 1).copy(goalBudgets = goalBudget),
            System.currentTimeMillis(),
        )
    }

    private fun assertInheritedGoalBudget(
        goalId: String,
        spentBeforeResume: Int,
    ) {
        val goal =
            compose
                .container()
                .storage.goals
                .resolve(goalId)
        assertEquals(goalBudget, goal.toRuntimeGoal().budgets)
        assertTrue(goal.modelCalls >= spentBeforeResume)
    }

    @Test fun explicitStopParksQueueAndFreshUserWorkDoesNotResurrectTheGoal() =
        runBlocking {
            fixture { chat, session, wire ->
                val storage = compose.container().storage
                val first = accepted(chat, submission(session, "Original fixture goal to stop"))
                wire.awaitRequest(0)
                val binding = requireNotNull(storage.goalTurnBindings.byTurn(first))
                val goal = storage.goalRuns.resolve(binding.runId).goalId
                val queued = submission(session, "Keep this queued text parked")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertNull(storage.turns.resolve(first).pauseRequestedAt)
                chat.stopTurn(first)
                wire.release(0)
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                assertEquals(TurnState.CANCELLED.name, storage.turns.resolve(first).state)
                assertEquals(
                    SessionInputState.NEEDS_ATTENTION,
                    storage.sessionInputs.get(queued.clientRequestId)?.state,
                )
                assertEquals(1, storage.goalRuns.listByGoal(goal).size)
                assertEquals(1, wire.count())

                // Terminal Room/UI state can precede release of the live execution lease.
                kotlinx.coroutines.withTimeout(15_000) {
                    while (!chat.setModeFromComposer(session, AgentMode.ACT)) kotlinx.coroutines.delay(25)
                }
                val fresh = accepted(chat, submission(session, "A new explicit user task after stopping"))
                wire.awaitRequest(1)
                assertNull(storage.goalTurnBindings.byTurn(fresh))
                val freshBody = wire.body(1)
                assertFalse(freshBody.contains("Keep this queued text parked"))
                assertFalse(wire.body(1).contains("[Goal continuation]"))
                wire.release(1)
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                assertFalse(wire.entered[2].await(500, TimeUnit.MILLISECONDS))
                assertEquals(2, wire.count())
                assertEquals(1, storage.goalRuns.listByGoal(goal).size)
                assertEquals(
                    SessionInputState.NEEDS_ATTENTION,
                    storage.sessionInputs.get(queued.clientRequestId)?.state,
                )
                assertNull(storage.sessionInputs.get(queued.clientRequestId)?.messageId)
                assertEquals(1, GoalSummaryQuery(storage).forSession(session).size)
            }
        }

    @Test fun automaticGoalRoundsKeepTransportForegroundUntilExplicitStop() =
        runBlocking {
            fixture { chat, session, wire ->
                val storage = compose.container().storage
                val first = accepted(chat, submission(session, "Continue the fixture goal through several rounds"))
                wire.awaitRequest(0)
                assertTrue(chat.foregroundTransportState.value in DataSyncForegroundController.TRANSPORT_ACTIVE)
                val phases = Collections.synchronizedList(mutableListOf<TurnState?>())
                // Run on the emitter thread so a transient idle emission cannot be hidden by a
                // blocked test thread or StateFlow collector scheduling behind the next request.
                val collector =
                    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        chat.foregroundTransportState.collect { phases.add(it) }
                    }
                try {
                    wire.release(0)
                    wire.awaitRequest(1)
                    wire.release(1)
                    wire.awaitRequest(2)
                    val observed = synchronized(phases) { phases.toList() }
                    assertTrue("The transport flow must be observed", observed.isNotEmpty())
                    assertTrue(
                        "Goal handoff emitted an idle phase: $observed",
                        observed.all { it in DataSyncForegroundController.TRANSPORT_ACTIVE },
                    )
                    val binding = requireNotNull(storage.goalTurnBindings.byTurn(first))
                    val goal = storage.goalRuns.resolve(binding.runId).goalId
                    assertEquals(3, storage.goalRuns.listByGoal(goal).size)
                } finally {
                    collector.cancelAndJoin()
                }
                val live = storage.turns.listBySession(session).single { !TurnState.valueOf(it.state).isTerminal }
                chat.stopTurn(live.id)
                wire.release(2)
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                assertNull(chat.foregroundTransportState.value)
                assertFalse(wire.unexpected.await(500, TimeUnit.MILLISECONDS))
            }
        }

    @Test
    fun explicitSteeringKeepsTheGoalTurnAndItsAccumulatedUsage() =
        runBlocking {
            fixture { chat, session, wire ->
                val storage = compose.container().storage
                val first = accepted(chat, submission(session, "Continue the original fixture objective"))
                wire.awaitRequest(0)
                val binding = requireNotNull(storage.goalTurnBindings.byTurn(first))
                val goal = storage.goalRuns.resolve(binding.runId).goalId
                val supplement =
                    submission(session, "Use this additional constraint").copy(
                        delivery = SessionInputDelivery.STEER,
                        expectedTurnId = first,
                    )
                assertTrue(chat.sendSubmission(supplement).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertNull(storage.turns.resolve(first).pauseRequestedAt)
                wire.release(0)
                wire.awaitRequest(1)
                val input = requireNotNull(storage.sessionInputs.get(supplement.clientRequestId))
                assertEquals(first, input.consumedTurnId)
                assertEquals(AgentMode.GOAL.name, input.configuration.mode)
                assertNotNull(input.requestModelCallId)
                assertEquals(1, storage.turns.listBySession(session).size)
                assertEquals(1, storage.goalRuns.listByGoal(goal).size)
                assertTrue(wire.body(1).contains("Use this additional constraint"))
                assertEquals(2, storage.modelCalls.listByTurn(first).size)
                assertEquals(
                    goalBudget,
                    storage.goals
                        .resolve(goal)
                        .toRuntimeGoal()
                        .budgets,
                )
                chat.stopTurn(first)
                wire.release(1)
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                assertEquals(TurnState.CANCELLED.name, storage.turns.resolve(first).state)
                assertTrue(storage.goals.resolve(goal).modelCalls >= 2)
                assertFalse(wire.entered[2].await(500, TimeUnit.MILLISECONDS))
            }
        }

    private suspend fun accepted(
        chat: ChatService,
        submission: ChatSubmission,
    ): String {
        val receipt = chat.sendSubmission(submission).await()
        return compose.awaitAdmittedTurn(receipt)
    }

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private class Wire {
        val entered = List(3) { CountDownLatch(1) }
        private val releases = List(3) { CountDownLatch(1) }
        private val bodies = Collections.synchronizedList(mutableListOf<String>())
        val unexpected = CountDownLatch(1)

        fun answer(body: String): String {
            val index =
                synchronized(bodies) {
                    bodies.add(body)
                    bodies.lastIndex
                }
            if (index in entered.indices) {
                entered[index].countDown()
                check(releases[index].await(30, TimeUnit.SECONDS)) {
                    "Goal scheduling fixture did not release response $index"
                }
            } else {
                unexpected.countDown()
            }
            return textAnswerStream("A completed fixture round")
        }

        fun awaitRequest(index: Int) {
            assertTrue("Expected model request ${index + 1}", entered[index].await(15, TimeUnit.SECONDS))
        }

        fun release(index: Int) = releases[index].countDown()

        fun releaseAll() = releases.forEach { it.countDown() }

        fun count(): Int = bodies.size

        fun body(index: Int): String = bodies[index]
    }

    @Test fun manualCompactionDoesNotCreateAGoalOrContinue() =
        runBlocking {
            manualCompactionFixture(existingGoal = false, cancel = false)
        }

    @Test fun manualCompactionPreservesPausedGoalAndUsage() =
        runBlocking {
            manualCompactionFixture(existingGoal = true, cancel = false)
        }

    @Test fun cancelledManualCompactionPreservesGoalAndHistory() =
        runBlocking {
            manualCompactionFixture(existingGoal = true, cancel = true)
        }

    @Test fun emptyHistoryCompactionAndRetryNeverBecomeGoalWork() =
        runBlocking {
            fixture { chat, session, wire ->
                val storage = compose.container().storage
                val first = accepted(chat, submission(session, "/compact"))
                compose.waitUntil(15_000) {
                    storage.turns.resolve(first).endedAt != null &&
                        !chat.screen.value.isSending && chat.screen.value.retryTargetTurnId == first
                }
                assertEquals("CONTEXT_NOT_COMPACTABLE", storage.turns.resolve(first).errorCode)
                chat.retry()
                compose.waitUntil(15_000) {
                    storage.turns.listBySession(session).size == 2 && !chat.screen.value.isSending
                }
                storage.turns.listBySession(session).forEach { assertNull(storage.goalTurnBindings.byTurn(it.id)) }
                assertEquals(0, wire.count())
                assertTrue(GoalSummaryQuery(storage).forSession(session).isEmpty())
                assertEquals(AgentMode.GOAL, chat.runControl.value.mode)
            }
        }

    @Suppress("LongMethod") // One real admission, model request and durable settlement assertion sequence.
    private suspend fun manualCompactionFixture(existingGoal: Boolean, cancel: Boolean) {
        fixture { chat, session, wire ->
            val storage = compose.container().storage
            val goal =
                if (existingGoal) chat.createGoal("Keep the original objective", emptyList(), goalBudget) else null
            if (goal != null) {
                storage.goals.updateGoal(storage.goals.resolve(goal).copy(state = "PAUSED"))
                storage.goalControls.insert(GoalControlEntity(goal, session, 0, null, null))
            }
            val before = goal?.let(storage.goals::resolve)
            repeat(3) {
                val old =
                    TurnCoordinator.start(
                        storage,
                        object : Clock {
                            override fun now() = java.time.Instant.now()
                        },
                        { UUID.randomUUID().toString() },
                        TurnStartSpec(
                            session,
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            "{}",
                            "Historical context ".repeat(600),
                        ),
                    )
                old.beginModelStream().apply {
                    apply(ModelEvent.TextDelta("Previous answer"))
                    apply(ModelEvent.Completed("stop"))
                }
                old.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
            val turn = accepted(chat, submission(session, "/compact"))
            wire.awaitRequest(0)
            assertNull(storage.goalTurnBindings.byTurn(turn))
            val request = Json.parseToJsonElement(wire.body(0)) as JsonObject
            assertTrue(request["tools"] == null || request["tools"].toString() == "[]")
            if (cancel) chat.stopTurn(turn)
            wire.release(0)
            compose.waitUntil(15_000) {
                storage.turns.resolve(turn).endedAt != null && !chat.screen.value.isSending
            }
            assertEquals(if (cancel) "CANCELLED" else "COMPLETED", storage.turns.resolve(turn).state)
            assertFalse(wire.entered[1].await(1, TimeUnit.SECONDS))
            assertEquals(1, wire.count())
            assertTrue(storage.toolCalls.listByTurn(turn).isEmpty())
            assertEquals(AgentMode.GOAL, chat.runControl.value.mode)
            if (goal == null) {
                assertTrue(GoalSummaryQuery(storage).forSession(session).isEmpty())
            } else {
                assertEquals(before, storage.goals.resolve(goal))
                assertTrue(storage.goalRuns.listByGoal(goal).isEmpty())
            }
            val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession(session))
            if (cancel) assertNull(checkpoint) else assertNotNull(checkpoint)
        }
    }

    private suspend fun fixture(block: suspend (ChatService, String, Wire) -> Unit) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val previous = chat.runControl.value
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider =
                container.providerService.create(
                    ProviderDraft(
                        null,
                        "Goal input fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                        "fixture-model-a",
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", server.port),
                        emptyList(),
                    ),
                    null,
                )
            check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            val session = chat.createSession("Goal input fixture", provider, "fixture-model-a")
            val wire = Wire()
            try {
                server.scriptedChat = wire::answer
                chat.openSession(session)
                chat.setMode(AgentMode.GOAL)
                chat.setTurnBudgets(TurnBudgets(4, 6, 64_000, 128, 100_000))
                configureGoalBudget(session)
                block(chat, session, wire)
            } finally {
                println("Goal fixture turns: " + container.storage.turns.listBySession(session))
                println("Goal fixture inputs: " + container.storage.sessionInputs.listPending(session))
                println("Goal fixture sending: " + chat.screen.value.isSending)
                chat.stop()
                wire.releaseAll()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                chat.closeSession()
                container.runControlStore.setMode(previous.mode)
                container.runControlStore.setBudgets(previous.budgets)
                container.runControlStore.setGoalBudgets(previous.goalBudgets)
                GoalSummaryQuery(container.storage).forSession(session).forEach {
                    container.privacyDeletionService.deleteGoal(it.id)
                }
                container.storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
            }
        }
    }
}
