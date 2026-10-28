package com.helix.core.agent.runtime

import com.helix.core.agent.CancelResult
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.TerminalCommitResult
import com.helix.core.agent.TurnReviewCheckpoint
import com.helix.core.agent.turn.CancelTurnCommand
import com.helix.core.agent.turn.ReviewResolutionCommand
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnCancellationReceipt
import com.helix.core.agent.turn.TurnLifecycleSnapshot
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTurnCommandHandlerTest {
    @Test fun durableCancelPrecedesSignalsAndDoesNotReleaseTheRunningExecutor() =
        fixture { f ->
            val live = f.claim("original")
            f.onApprovalCancel = {
                assertEquals(TurnState.CANCELLING, f.store.findTurn("original")?.phase)
                assertTrue(live.cancel.isCancelled())
                assertFalse(live.job.isCancelled)
            }
            assertEquals(CancelResult.StopAccepted, f.handler.cancel(TurnId("original")))
            assertSame(live, f.engine.liveExecution.active("session"))
            assertFalse(live.job.isCompleted)
            f.onApprovalCancel = {}
            assertEquals(CancelResult.StopAccepted, f.handler.cancel(TurnId("original")))
            assertSame(live, f.engine.liveExecution.active("session"))
        }

    @Test fun cancellingOldTerminalTurnCannotDisarmOrSignalItsSuccessor() =
        fixture { f ->
            f.store.turns["old"] = TurnLifecycleSnapshot("old", "session", TurnState.COMPLETED)
            val successor = f.claim("successor")
            f.delivery.handoffs["session"] = "successor"
            assertEquals(CancelResult.AlreadyTerminal(TurnState.COMPLETED), f.handler.cancel(TurnId("old")))
            assertFalse(successor.cancel.isCancelled())
            assertEquals("successor", f.delivery.handoffOwner("session"))
            assertTrue(f.delivery.disarmedSessions.isEmpty())
            assertTrue(f.delivery.disarmedTurns.isEmpty())
            assertEquals(listOf("old"), f.delivery.forgotten)
            assertFalse(
                f.store.cancellations
                    .single()
                    .parkTerminalDelivery,
            )
        }

    @Test fun missingReviewAndNoLiveTurnDoNotFabricateStoppedExecution() =
        fixture { f ->
            assertEquals(CancelResult.NotFound, f.handler.cancel(TurnId("missing")))
            f.store.turns["review"] = TurnLifecycleSnapshot("review", "session", TurnState.NEEDS_REVIEW)
            assertEquals(CancelResult.ReviewRequired, f.handler.cancel(TurnId("review")))
            assertEquals(TurnState.NEEDS_REVIEW, f.store.findTurn("review")?.phase)
            f.store.turns["unowned"] = TurnLifecycleSnapshot("unowned", "session", TurnState.WAITING_MODEL)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { f.handler.cancel(TurnId("unowned")) }
            }
            assertEquals(TurnState.WAITING_MODEL, f.store.findTurn("unowned")?.phase)
            assertEquals(0, f.approvalCancellations)
        }

    @Test fun sessionAndReasonChecksHappenBeforeAnyStopMutation() =
        fixture { f ->
            val live = f.claim("original")
            assertThrows(IllegalStateException::class.java) {
                runBlocking { f.handler.stopScheduled("original", "foreign-session", "SCHEDULED_TIMEOUT") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.handler.stopScheduled("original", "session", "USER_STOP") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.handler.stopTask("original", false, "UNTRUSTED_REASON") }
            }
            assertTrue(f.store.cancellations.isEmpty())
            assertTrue(f.delivery.forgotten.isEmpty())
            assertFalse(live.cancel.isCancelled())
        }

    @Test fun scheduledAndGoalPauseUseTheExistingDurableCommands() =
        fixture { f ->
            val live = f.claim("original")
            f.handler.stopTask("original", true, "FGS_TIMEOUT")
            assertEquals(listOf("original"), f.store.pauseRequests)
            assertTrue(f.store.cancellations.isEmpty())
            assertFalse(live.cancel.isCancelled())
            assertEquals(CancelResult.StopAccepted, f.handler.stopScheduled("original", "session", "SCHEDULED_TIMEOUT"))
            assertEquals(
                "SCHEDULED_TIMEOUT",
                f.store.cancellations
                    .single()
                    .reason,
            )
        }

    @Test fun terminalHandoffIsRevokedOnlyForItsOriginalOwner() =
        fixture { f ->
            f.store.turns["original"] = TurnLifecycleSnapshot("original", "session", TurnState.COMPLETED)
            f.delivery.handoffs["session"] = "original"
            assertEquals(CancelResult.AlreadyTerminal(TurnState.COMPLETED), f.handler.cancel(TurnId("original")))
            assertTrue(
                f.store.cancellations
                    .single()
                    .parkTerminalDelivery,
            )
            assertEquals(listOf("session"), f.delivery.disarmedSessions)
        }

    @Test fun callerCancellationDoesNotAbortTheAcceptedStopCommand() =
        fixture { f ->
            val live = f.claim("original")
            val caller =
                CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    f.handler.cancel(TurnId("original"))
                }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(TurnState.CANCELLING, f.store.findTurn("original")?.phase)
            assertTrue(live.cancel.isCancelled())
            assertFalse(live.job.isCompleted)
        }

    private fun fixture(block: suspend (Fixture) -> Unit) =
        runBlocking {
            val f = Fixture()
            try {
                block(f)
            } finally {
                f.release.complete(Unit)
                f.worker?.join()
                f.scope.cancel()
            }
        }

    private class Fixture {
        val store = Store()
        val engine = TurnEngine(store, TurnDiagnostics { _, _ -> })
        val delivery = Delivery()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var worker: Job? = null
        var approvalCancellations = 0
        var onApprovalCancel: () -> Unit = {}
        val handler =
            StopTurnCommandHandler(
                engine,
                delivery,
                PendingTurnApprovalCancellation {
                    approvalCancellations++
                    onApprovalCancel()
                },
            )

        suspend fun claim(id: String): LiveTurnHandle {
            store.turns[id] = TurnLifecycleSnapshot(id, "session", TurnState.WAITING_MODEL)
            val entered = CompletableDeferred<Unit>()
            val job =
                scope.launch {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            worker = job
            entered.await()
            return engine.liveExecution.claim(
                "session",
                id,
                job,
                RunControlConfig(AgentMode.CHAT, false, TurnBudgets(maxOutputTokens = 1024)),
            )
        }
    }

    private class Delivery : TurnDeliveryControl {
        val handoffs = mutableMapOf<String, String>()
        val disarmedTurns = mutableListOf<String>()
        val disarmedSessions = mutableListOf<String>()
        val forgotten = mutableListOf<String>()

        override fun handoffOwner(sessionId: String): String? = handoffs[sessionId]

        override fun disarmTurn(
            sessionId: String,
            turnId: String,
        ) {
            disarmedTurns += turnId
            if (handoffs[sessionId] == turnId) handoffs.remove(sessionId)
        }

        override fun disarm(sessionId: String) {
            disarmedSessions += sessionId
            handoffs.remove(sessionId)
        }

        override fun forgetUserRequests(turnId: String) {
            forgotten += turnId
        }
    }

    private class Store : TurnLifecycleStore {
        val turns = java.util.concurrent.ConcurrentHashMap<String, TurnLifecycleSnapshot>()
        val cancellations = mutableListOf<CancelTurnCommand>()
        val pauseRequests = mutableListOf<String>()

        override fun findTurn(turnId: String): TurnLifecycleSnapshot? = turns[turnId]

        override fun persistedPhase(turnId: String): TurnState? = turns[turnId]?.phase

        override fun persistedAssistantText(turnId: String): String? = null

        override fun requestGoalPause(turnId: String): Boolean {
            pauseRequests += turnId
            return false
        }

        override fun cancel(command: CancelTurnCommand): TurnCancellationReceipt {
            cancellations += command
            val turn = turns[command.turnId] ?: return TurnCancellationReceipt.NotFound
            check(turn.sessionId == command.sessionId)
            return when {
                turn.phase.isTerminal -> {
                    TurnCancellationReceipt.AlreadyTerminal(turn.phase)
                }

                turn.phase == TurnState.NEEDS_REVIEW -> {
                    TurnCancellationReceipt.ReviewRequired
                }

                command.hasLiveDriver -> {
                    turns[turn.turnId] = turn.copy(phase = TurnState.CANCELLING)
                    TurnCancellationReceipt.SignalLive
                }

                else -> {
                    error("non-terminal Turn has no live driver and is not parked")
                }
            }
        }

        override fun admit(command: TurnAdmissionCommand) = unexpected()

        override fun submissionReceipt(
            clientRequestId: String,
            sessionId: String,
            inputFingerprint: String,
        ) = unexpected()

        override fun recoveryPredecessor(
            sessionId: String,
            clientRequestId: String,
        ) = unexpected()

        override fun sessionBlocker(sessionId: String) = unexpected()

        override suspend fun commitTerminal(command: TerminalCommitCommand): TerminalCommitResult = unexpected()

        override fun parkForReview(checkpoint: TurnReviewCheckpoint) = unexpected()

        override suspend fun resolveReview(command: ReviewResolutionCommand) = unexpected()

        override fun recoverOnStartup() = unexpected()

        override fun finishAutomaticRecovery(
            parentId: String,
            reason: String,
            inspectionFailed: Boolean,
        ) = unexpected()

        override fun prepareTerminalRelease(
            turnId: String,
            sessionId: String,
            outcome: ModelStreamTerminal,
        ) = unexpected()

        override fun parkFailedTerminalDelivery(
            turnId: String,
            sessionId: String,
        ) = unexpected()

        override fun parkUnknownDelivery(
            turnId: String,
            sessionId: String,
        ) = unexpected()

        private fun unexpected(): Nothing = error("Stop command cannot perform admission, recovery, or settlement")
    }
}
