package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.SessionInputState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No injected admission result: the first real Goal spends its last model call and becomes BLOCKED. */
class SessionInputAdmissionFailureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exhaustedGoalEndsRejectedInputAfterOneRecoveryWithoutCallingTheModelAgain() =
        runBlocking {
            fixture(maxCalls = 1, holdFirst = false) { scenario ->
                val chat = scenario.chat
                val storage = compose.container().storage
                val first = chat.sendSubmission(submission(scenario.session, "Complete one fixture goal round")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(scenario.entered.await(15, TimeUnit.SECONDS))
                val binding = requireNotNull(storage.goalTurnBindings.byTurn(firstTurn))
                val goal = storage.goalRuns.resolve(binding.runId).goalId
                compose.waitUntil(15_000) {
                    !chat.screen.value.isSending && storage.goals.resolve(goal).state == "BLOCKED"
                }
                // Explicitly revoke the finished round's activation before submitting another Goal-mode
                // request; this avoids depending on whether the asynchronous drain already disarmed it.
                chat.stopTurn(firstTurn)
                val rejectedStart = submission(scenario.session, "Try another round of the same exhausted goal")
                val receipt = chat.sendSubmission(rejectedStart).await()
                assertTrue(receipt.outcome is ChatSubmissionOutcome.Enqueued)
                // Enqueued is acceptance, not the asynchronous scheduler's admission verdict.
                compose.waitUntil(15_000) {
                    storage.sessionInputs.get(rejectedStart.clientRequestId)?.state == SessionInputState.FAILED
                }
                val parked = requireNotNull(storage.sessionInputs.get(rejectedStart.clientRequestId))
                assertEquals(SessionInputState.FAILED, parked.state)
                assertEquals("INPUT_RECOVERY_INTERRUPTED", parked.blockedReason)
                assertEquals(
                    1,
                    storage.auditEvents.listByCorrelation(scenario.session).count {
                        it.id == "input-recovery:${parked.inputId}"
                    },
                )
                assertEquals(
                    1,
                    storage.messages.listBySession(scenario.session).count {
                        it.id == "input-ended:${parked.inputId}" && it.kind == "RECOVERY_NOTICE"
                    },
                )
                assertNull(parked.consumedTurnId)
                assertNull(parked.messageId)
                assertNull(parked.requestModelCallId)
                assertEquals(1, storage.turns.listBySession(scenario.session).size)
                assertEquals(1, scenario.requests.get())

                assertTrue(chat.sendSubmission(rejectedStart).await().outcome is ChatSubmissionOutcome.Enqueued)
                assertEquals(parked, storage.sessionInputs.get(parked.inputId))
                assertEquals(rejectedStart.text, chat.readSessionInput(parked.inputId).await())
                assertFalse(scenario.extraRequest.await(500, TimeUnit.MILLISECONDS))
                assertEquals(1, storage.turns.listBySession(scenario.session).size)
            }
        }

    @Test fun stopParkedInputKeepsItsReasonAndRevisionWhenOldSubmissionIsRedelivered() =
        runBlocking {
            fixture(maxCalls = 4, holdFirst = true) { scenario ->
                val chat = scenario.chat
                val storage = compose.container().storage
                val first = chat.sendSubmission(submission(scenario.session, "Hold the active fixture goal")).await()
                val firstTurn = compose.awaitAdmittedTurn(first)
                assertTrue(scenario.entered.await(15, TimeUnit.SECONDS))
                val queued = submission(scenario.session, "Preserve this queued request after stop")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                chat.stopTurn(firstTurn)
                scenario.release.countDown()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals(TurnState.CANCELLED.name, storage.turns.resolve(firstTurn).state)
                assertEquals(SessionInputState.NEEDS_ATTENTION, parked.state)
                assertEquals("USER_STOP", parked.blockedReason)
                repeat(2) {
                    assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                }
                assertEquals(parked, storage.sessionInputs.get(parked.inputId))
                assertNull(storage.sessionInputs.get(parked.inputId)?.messageId)
                assertFalse(scenario.extraRequest.await(500, TimeUnit.MILLISECONDS))
                assertEquals(1, scenario.requests.get())
                assertEquals(1, storage.turns.listBySession(scenario.session).size)
            }
        }

    @Test fun oldSubmissionCannotReplaceEditedUserParkedInput() =
        runBlocking {
            fixture(maxCalls = 4, holdFirst = true) { scenario ->
                val chat = scenario.chat
                val storage = compose.container().storage
                val first = chat.sendSubmission(submission(scenario.session, "Hold for editing")).await()
                val turn = compose.awaitAdmittedTurn(first)
                assertTrue(scenario.entered.await(15, TimeUnit.SECONDS))
                val queued = submission(scenario.session, "Original queued text")
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                chat.stopTurn(turn)
                scenario.release.countDown()
                compose.waitUntil(15_000) { !chat.screen.value.isSending }
                val parked = requireNotNull(storage.sessionInputs.get(queued.clientRequestId))
                assertEquals("USER_STOP", parked.blockedReason)
                val update = chat.editSessionInput(parked.inputId, parked.revision, "Keep this corrected request")
                assertTrue(update.await())
                val edited = requireNotNull(storage.sessionInputs.get(parked.inputId))
                assertEquals(SessionInputState.NEEDS_ATTENTION, edited.state)
                assertEquals("INPUT_EDITED", edited.blockedReason)
                assertTrue(chat.sendSubmission(queued).await().outcome is ChatSubmissionOutcome.Enqueued)
                val stale = chat.resumeSessionInput(parked.inputId, parked.revision).await()
                assertTrue(stale is ChatSubmissionOutcome.Rejected)
                assertEquals(edited, storage.sessionInputs.get(parked.inputId))
                assertEquals("Keep this corrected request", chat.readSessionInput(parked.inputId).await())
                assertNull(edited.consumedTurnId)
                assertNull(edited.messageId)
                assertNull(edited.requestModelCallId)
                assertFalse(scenario.extraRequest.await(500, TimeUnit.MILLISECONDS))
                assertEquals(1, storage.turns.listBySession(scenario.session).size)
            }
        }

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private class Scenario(
        val chat: ChatService,
        val session: String,
    ) {
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extraRequest = CountDownLatch(1)

        fun answer(holdFirst: Boolean): String {
            if (requests.incrementAndGet() == 1) {
                entered.countDown()
                if (holdFirst) {
                    check(release.await(30, TimeUnit.SECONDS)) {
                        "Input admission fixture did not release its response"
                    }
                }
            } else {
                extraRequest.countDown()
            }
            return textAnswerStream("One completed fixture round")
        }
    }

    private suspend fun fixture(
        maxCalls: Int,
        holdFirst: Boolean,
        block: suspend (Scenario) -> Unit,
    ) {
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
                        "Input admission fixture",
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
            val session = chat.createSession("Input admission fixture", provider, "fixture-model-a")
            val scenario = Scenario(chat, session)
            try {
                server.scriptedChat = { scenario.answer(holdFirst) }
                chat.openSession(session)
                chat.setMode(AgentMode.GOAL)
                chat.setTurnBudgets(TurnBudgets(4, 6, 64_000, 128, 100_000))
                configureGoalBudget(session, maxCalls)
                block(scenario)
            } finally {
                chat.stop()
                scenario.release.countDown()
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

    private fun configureGoalBudget(
        session: String,
        maxCalls: Int,
    ) {
        val container = compose.container()
        compose.waitUntil(15_000) {
            container.storage.sessionRunControls.forSession(session)?.let {
                it.mode == AgentMode.GOAL && it.budgets == TurnBudgets(4, 6, 64_000, 128, 100_000)
            } == true
        }
        val controls =
            com.helix.app.runcontrol
                .SessionRunControlStore(container.storage, container.runControlStore)
        controls.set(
            session,
            controls.ensure(session, 1).copy(goalBudgets = GoalBudgets(maxCalls, 8, 300_000, 120_000, 60_000, 0)),
            System.currentTimeMillis(),
        )
    }
}
