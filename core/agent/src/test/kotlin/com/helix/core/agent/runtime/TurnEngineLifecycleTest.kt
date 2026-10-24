package com.helix.core.agent.runtime

import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnLoopResult
import com.helix.core.agent.turn.AdmittedTurn
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionIdentity
import com.helix.core.agent.turn.TurnAdmissionInput
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnCancellationReceipt
import com.helix.core.agent.turn.TurnModelBinding
import com.helix.core.agent.turn.TurnTerminalReleaseReceipt
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
class TurnEngineLifecycleTest {
    @Test fun admittedTurnCommitsTextBeforeReleasingTheSameSessionAndPublishingTerminal() =
        runBlocking {
            val fixture = Fixture()
            val running =
                fixture.launch(this) { request ->
                    assertSame(fixture.admitted.coordinator, request.coordinator)
                    request.coordinator.beginModelStream().apply(ModelEvent.TextDelta("accepted answer"))
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.COMPLETED, fixture.store.persistedPhase("turn"))
            assertEquals("accepted answer", fixture.engine.runtimeView.persistedAssistantText("turn"))
            val expectedEvents = listOf("commit:COMPLETED", "prepare", "before-terminal", "after-terminal:true")
            assertEquals(expectedEvents, fixture.events)
            assertEquals(listOf(TurnState.COMPLETED), running.observations.map { it.state })
            assertEquals(
                "session",
                fixture.store.terminalCommands
                    .single()
                    .checkpoint.sessionId,
            )
            assertEquals(
                "model-call",
                fixture.store.terminalCommands
                    .single()
                    .checkpoint.modelCallId,
            )
            assertNull(fixture.engine.liveExecution.active("session"))
            assertTrue(
                fixture.engine.runtimeView
                    .observe("turn")
                    .toList()
                    .isEmpty(),
            )
            assertEquals(1, fixture.modelRuns)
        }

    @Test fun actualJobCancellationPersistsCancelledBeforeReleasingOwnership() =
        runBlocking {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val running =
                fixture.launch(this) { request ->
                    request.coordinator.beginModelStream()
                    entered.complete(Unit)
                    awaitCancellation()
                }
            entered.await()
            assertEquals(
                TurnCancellationReceipt.SignalLive,
                fixture.engine.requestCancel("turn", "session", true, false, "USER_STOP"),
            )
            fixture.engine.liveExecution
                .byTurn("turn")!!
                .signalCancel()
            running.job.cancelAndJoin()
            running.collector.join()

            assertEquals(TurnState.CANCELLED, fixture.store.persistedPhase("turn"))
            assertEquals(listOf(TurnState.CANCELLED), running.observations.map { it.state })
            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun settledUnknownToolBatchParksForReviewWithoutTerminalOrAnotherModelCall() =
        runBlocking {
            val fixture = Fixture()
            val running =
                fixture.launch(this) { request ->
                    request.coordinator.beginModelStream()
                    request.coordinator.beginToolBatch(listOf("first", "second"))
                    request.coordinator.commitModelToolStep(
                        """[{"id":"first","name":"read","arguments":"{}"},""" +
                            """{"id":"second","name":"read","arguments":"{}"}]""",
                    )
                    request.coordinator.settleBatchCall("second", true)
                    request.coordinator.settleBatchCall("first", false)
                    TurnLoopResult.ParkedForReview(listOf("second"))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.NEEDS_REVIEW, fixture.store.persistedPhase("turn"))
            assertEquals(listOf("second"), fixture.store.reviewCheckpoint!!.reviewCallIds)
            assertEquals(
                listOf("first", "second"),
                fixture.store.reviewCheckpoint!!
                    .batchCalls.keys
                    .toList(),
            )
            assertTrue(fixture.store.terminalCommands.isEmpty())
            assertEquals(listOf("review", "before-review", "after-review"), fixture.events)
            assertEquals(listOf(TurnState.NEEDS_REVIEW), running.observations.map { it.state })
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun rejectedTerminalCommitParksDeliveryWithoutClaimingFailedOrReexecuting() =
        runBlocking {
            val fixture = Fixture().apply { store.rejectTerminal = true }
            val running =
                fixture.launch(this) {
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.WAITING_MODEL, fixture.store.persistedPhase("turn"))
            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(listOf("commit-rejected", "unknown", "before-unknown", "after-unknown"), fixture.events)
            assertTrue(running.observations.isEmpty())
            assertTrue(fixture.store.deliveryParked)
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun unavailableTerminalStoreIsNotRetriedAsFailedOrReexecuted() =
        runBlocking {
            val fixture = Fixture().apply { store.failTerminal = true }
            val running =
                fixture.launch(this) {
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.WAITING_MODEL, fixture.store.persistedPhase("turn"))
            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(listOf("commit-threw", "unknown", "before-unknown", "after-unknown"), fixture.events)
            assertTrue(running.observations.isEmpty())
            assertTrue(fixture.store.deliveryParked)
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun cancellationFromTerminalStoreKeepsJobCancelledWithoutASecondCommit() =
        runBlocking {
            val fixture = Fixture().apply { store.terminalFailure = CancellationException("store cancelled") }
            val running =
                fixture.launch(this) {
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            running.job.join()
            running.collector.join()

            assertTrue(running.job.isCancelled)
            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(1, fixture.modelRuns)
            assertEquals(TurnState.WAITING_MODEL, fixture.store.persistedPhase("turn"))
            assertEquals(listOf("commit-threw", "unknown", "before-unknown", "after-unknown"), fixture.events)
            assertTrue(fixture.store.deliveryParked)
            assertTrue(running.observations.isEmpty())
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun modelFailureThenTerminalStoreFailureDoesNotAttemptAnotherTerminal() =
        runBlocking {
            val fixture = Fixture().apply { store.failTerminal = true }
            val running = fixture.launch(this) { error("synthetic model boundary failure") }
            running.job.join()
            running.collector.join()

            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(
                TurnState.FAILED,
                fixture.store.terminalCommands
                    .single()
                    .outcome.state,
            )
            assertEquals(1, fixture.modelRuns)
            assertEquals(TurnState.WAITING_MODEL, fixture.store.persistedPhase("turn"))
            assertEquals(listOf("commit-threw", "unknown", "before-unknown", "after-unknown"), fixture.events)
            assertTrue(fixture.store.deliveryParked)
            assertTrue(running.observations.isEmpty())
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun postCommitPreparationFailureCannotCommitAgainOrRestartTheModel() =
        runBlocking {
            val fixture = Fixture().apply { store.failPreparation = true }
            val running =
                fixture.launch(this) {
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.COMPLETED, fixture.store.persistedPhase("turn"))
            assertEquals(1, fixture.store.terminalCommands.size)
            assertEquals(
                listOf(
                    "commit:COMPLETED",
                    "prepare",
                    "terminal-delivery-park",
                    "before-unknown",
                    "after-terminal:false",
                ),
                fixture.events,
            )
            assertEquals(listOf(TurnState.COMPLETED), running.observations.map { it.state })
            assertTrue(fixture.diagnostics.any { it.contains("delivery preparation failed") })
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun postCommitProjectionFailureDoesNotChangeTheDurableTerminal() =
        runBlocking {
            val fixture = Fixture().apply { failProjection = true }
            val running =
                fixture.launch(this) {
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "NETWORK"))
                }
            running.job.join()
            running.collector.join()

            assertEquals(TurnState.FAILED, fixture.store.persistedPhase("turn"))
            assertEquals(
                "NETWORK",
                fixture.store.terminalCommands
                    .single()
                    .outcome.errorCode,
            )
            assertEquals(listOf("commit:FAILED", "prepare", "before-terminal", "after-terminal:false"), fixture.events)
            assertTrue(running.observations.single().retryable)
            assertEquals(1, fixture.modelRuns)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun liveSessionCannotBeCancelledUsingAnotherSessionIdentity() =
        runBlocking {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val running =
                fixture.launch(this) {
                    entered.complete(Unit)
                    finish.await()
                    TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                }
            entered.await()
            assertEquals(
                TurnCancellationReceipt.NotFound,
                fixture.engine.requestCancel("turn", "different-session", true, false, "USER_STOP"),
            )
            assertFalse(
                fixture.engine.liveHandles
                    .cancelSignal("turn")!!
                    .isCancelled(),
            )
            finish.complete(Unit)
            running.job.join()
            running.collector.join()
            assertEquals(TurnState.COMPLETED, fixture.store.persistedPhase("turn"))
            assertNull(fixture.engine.liveExecution.active("different-session"))
        }

    @Test fun actualAgentLoopPersistsManifestBeforeProviderAndCommitsStreamedAnswer() =
        runBlocking {
            val fixture = Fixture()
            val loop =
                EngineLoopFixture(fixture.admitted.coordinator, fixture.store) {
                    listOf(ModelEvent.TextDelta("actual loop answer"), ModelEvent.Completed("stop"))
                }
            val running = fixture.launchActualLoop(this, loop)
            running.job.join()
            running.collector.join()

            assertEquals(1, loop.requests.size)
            assertTrue(loop.dispatched.isEmpty())
            assertTrue(fixture.store.log.indexOf("manifest:model-call") < fixture.store.log.indexOf("wire:0"))
            assertEquals("actual loop answer", fixture.store.persistedAssistantText("turn"))
            assertEquals(TurnState.COMPLETED, fixture.store.persistedPhase("turn"))
            assertEquals(1, fixture.store.terminalCommands.size)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun actualAgentLoopBackfillsDeniedResultsInOriginalCallOrderWithoutExecutingAgain() =
        runBlocking {
            val fixture = Fixture()
            val loop =
                EngineLoopFixture(fixture.admitted.coordinator, fixture.store) { index ->
                    if (index ==
                        0
                    ) {
                        toolCalls()
                    } else {
                        listOf(ModelEvent.TextDelta("Denied; stopped."), ModelEvent.Completed("stop"))
                    }
                }.apply { denied = true }
            val running = fixture.launchActualLoop(this, loop)
            running.job.join()
            running.collector.join()

            assertEquals(2, loop.requests.size)
            val batch = loop.dispatched.single()
            assertEquals(batch.calls.map { it.callId }.reversed(), loop.completionOrder)
            val backfill =
                loop.requests
                    .last()
                    .messages
                    .filter { it.role == ModelRole.TOOL }
            assertEquals(listOf("wire-a", "wire-b"), backfill.map { it.toolCallId!!.value })
            assertEquals(listOf("POLICY_DENIED", "POLICY_DENIED"), backfill.map { it.text })
            assertEquals(
                listOf("POLICY_DENIED", "POLICY_DENIED"),
                fixture.store.history
                    .filter {
                        it.role == ModelRole.TOOL
                    }.map { it.text },
            )
            assertTrue(fixture.store.log.indexOf("results-committed") < fixture.store.log.indexOf("wire:1"))
            assertEquals(TurnState.COMPLETED, fixture.store.persistedPhase("turn"))
            assertEquals(1, fixture.store.terminalCommands.size)
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    @Test fun actualAgentLoopParksUnknownEffectsWithoutBackfillOrAnotherProviderRequest() =
        runBlocking {
            val fixture = Fixture()
            val loop =
                EngineLoopFixture(
                    fixture.admitted.coordinator,
                    fixture.store,
                ) { toolCalls() }.apply { unknown = true }
            val running = fixture.launchActualLoop(this, loop)
            running.job.join()
            running.collector.join()

            assertEquals(1, loop.requests.size)
            assertEquals(1, loop.dispatched.size)
            assertEquals(
                loop.dispatched
                    .single()
                    .calls
                    .map { it.callId },
                fixture.store.reviewCheckpoint!!.reviewCallIds,
            )
            assertTrue(fixture.store.history.none { it.role == ModelRole.TOOL })
            assertTrue(fixture.store.committedDrafts.isEmpty())
            assertTrue(fixture.store.terminalCommands.isEmpty())
            assertEquals(TurnState.NEEDS_REVIEW, fixture.store.persistedPhase("turn"))
            assertEquals(listOf(TurnState.NEEDS_REVIEW), running.observations.map { it.state })
            assertNull(fixture.engine.liveExecution.active("session"))
        }

    private fun toolCalls(): List<ModelEvent> =
        buildList {
            listOf("wire-a", "wire-b").forEachIndexed { index, id ->
                add(ModelEvent.ToolCallStarted(index, ToolCallId(id), "read"))
                add(ModelEvent.ToolArgumentsDelta(index, "{}"))
                add(ModelEvent.ToolCallFinished(index))
            }
            add(ModelEvent.Completed("tool_calls"))
        }

    private class Fixture : TurnExecutionHooks {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val store = LifecycleTestStore(events)
        val engine = TurnEngine(store) { message, _ -> diagnostics += message }
        private val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(4096))
        val admitted: AdmittedTurn =
            (
                engine.admit(
                    TurnAdmissionCommand(
                        TurnAdmissionIdentity("session", "turn", "model-call"),
                        TurnModelBinding("provider", "model", "frozen-model-binding"),
                        TurnAdmissionInput("synthetic input"),
                        control,
                        GoalWakeReason.USER_OPEN,
                        "model",
                    ),
                ) as TurnAdmissionResult.Started
            ).turn
        var modelRuns = 0
        var failProjection = false

        fun launch(
            scope: CoroutineScope,
            run: suspend (TurnExecutionRequest) -> TurnLoopResult,
        ): Running {
            engine.launchExecution(
                scope,
                { request ->
                    modelRuns++
                    run(request)
                },
                TurnExecutionRequest("session", admitted.coordinator, "provider", null, control),
                this,
            )
            val job = requireNotNull(engine.liveExecution.byTurn("turn")).job
            val observations = mutableListOf<TurnObservation>()
            val collector =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    observations += engine.runtimeView.observe("turn").toList()
                }
            return Running(job, collector, observations)
        }

        fun launchActualLoop(
            scope: CoroutineScope,
            fixture: EngineLoopFixture,
        ): Running {
            val loop = fixture.loop()
            return launch(scope) { request ->
                loop.runToolLoop(
                    request.sessionId,
                    request.coordinator,
                    request.providerId,
                    request.retryTurnId,
                    request.control,
                    request.entry,
                )
            }
        }

        override fun beforeExecution(turnId: String) {
            assertEquals("turn", turnId)
            assertEquals(TurnState.WAITING_MODEL, store.persistedPhase(turnId))
            assertEquals("session", engine.liveExecution.byTurn(turnId)!!.sessionId)
        }

        override fun beforeTerminalRelease(
            turnId: String,
            outcome: ModelStreamTerminal,
            release: TurnTerminalReleaseReceipt,
        ): TurnTerminalProjection {
            assertEquals(outcome.state, store.persistedPhase(turnId))
            assertTrue(engine.liveExecution.hasActive("session"))
            events += "before-terminal"
            check(!failProjection) { "injected projection failure" }
            return TurnTerminalProjection(true, null)
        }

        override fun afterTerminalRelease(
            turnId: String,
            outcome: ModelStreamTerminal,
            continueDelivery: Boolean,
        ) {
            assertEquals(outcome.state, store.persistedPhase(turnId))
            assertNull(engine.liveExecution.byTurn(turnId))
            events += "after-terminal:$continueDelivery"
        }

        override fun beforeReviewRelease(turnId: String): String? {
            assertEquals(TurnState.NEEDS_REVIEW, store.persistedPhase(turnId))
            assertTrue(engine.liveExecution.hasActive("session"))
            events += "before-review"
            return null
        }

        override fun afterReviewRelease(turnId: String) {
            assertNull(engine.liveExecution.byTurn(turnId))
            events += "after-review"
        }

        override fun beforeUnknownRelease(turnId: String) {
            assertTrue(store.deliveryParked)
            assertTrue(engine.liveExecution.hasActive("session"))
            events += "before-unknown"
        }

        override fun afterUnknownRelease(turnId: String) {
            assertNull(engine.liveExecution.byTurn(turnId))
            events += "after-unknown"
        }
    }

    private data class Running(
        val job: Job,
        val collector: Job,
        val observations: List<TurnObservation>,
    )
}
