package com.helix.app.application.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnStartSpec
import com.helix.app.application.agent.GoalContinuationDriver
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.CancelResult
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.runtime.LiveTurnHandle
import com.helix.core.agent.runtime.PendingTurnApprovalCancellation
import com.helix.core.agent.runtime.StopTurnCommandHandler
import com.helix.core.agent.runtime.TurnDeliveryControl
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
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
import java.io.File
import java.time.Instant
import java.util.UUID

/** Calls the production command handler against real Room facts and the actual live registry. */
class StopTurnCommandHandlerDeviceTest {
    @Test
    fun durableStopPrecedesSignalAndRetainsLiveCapacityUntilExecutorExits() =
        withFixture { f ->
            val live = f.claimLive()
            f.onApprovalCancel = {
                assertEquals(
                    "CANCELLING",
                    f.storage.turns
                        .resolve("turn")
                        .state,
                )
                assertTrue(live.cancel.isCancelled())
                assertFalse(live.job.isCancelled)
            }
            assertEquals(CancelResult.StopAccepted, f.handler.cancel(TurnId("turn")))
            assertFalse(live.job.isCompleted)
            assertSame(live, f.engine.liveExecution.active("session"))
            assertThrows(IllegalArgumentException::class.java) {
                f.engine.liveExecution.claim("session", "other-turn", Job(), f.control)
            }
            f.onApprovalCancel = {}
            assertEquals(CancelResult.StopAccepted, f.handler.cancel(TurnId("turn")))
            assertEquals(
                "CANCELLING",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertSame(live, f.engine.liveExecution.active("session"))
        }

    @Test
    fun terminalAndUnknownReviewAreNotRewrittenAsCancelled() =
        withFixture { f ->
            f.setPhase(TurnState.CANCELLING)
            f.setPhase(TurnState.NEEDS_REVIEW, "SIDE_EFFECT_UNKNOWN")
            assertEquals(CancelResult.ReviewRequired, f.handler.cancel(TurnId("turn")))
            assertEquals(
                "NEEDS_REVIEW",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertEquals(0, f.approvalCancellations)
            f.setPhase(TurnState.INTERRUPTED)
            assertEquals(CancelResult.AlreadyTerminal(TurnState.INTERRUPTED), f.handler.cancel(TurnId("turn")))
            assertEquals(
                "INTERRUPTED",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertEquals(CancelResult.NotFound, f.handler.cancel(TurnId("missing")))
            assertEquals(0, f.approvalCancellations)
        }

    @Test
    fun completedTurnStopIsIdempotent() =
        withFixture { f ->
            f.setPhase(TurnState.RECEIVING_MODEL)
            f.setPhase(TurnState.COMPLETED)
            repeat(2) {
                assertEquals(CancelResult.AlreadyTerminal(TurnState.COMPLETED), f.handler.cancel(TurnId("turn")))
            }
            assertEquals(
                "COMPLETED",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertEquals(0, f.approvalCancellations)
        }

    @Test
    fun scheduledMismatchAndInvalidReasonsCannotMutateOrSignal() =
        withFixture { f ->
            val live = f.claimLive()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { f.handler.stopScheduled("turn", "other-session", "SCHEDULED_TIMEOUT") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.handler.stopScheduled("turn", "session", "USER_STOP") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { f.handler.stopTask("turn", false, "UNTRUSTED_REASON") }
            }
            assertEquals(
                "WAITING_MODEL",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertFalse(live.cancel.isCancelled())
            assertEquals(0, f.approvalCancellations)
        }

    @Test
    fun scheduledStopUsesOriginalTargetAndDurableAdmission() =
        withFixture { f ->
            val live = f.claimLive()
            assertEquals(CancelResult.StopAccepted, f.handler.stopScheduled("turn", "session", "SCHEDULED_TIMEOUT"))
            assertEquals(
                "CANCELLING",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertTrue(live.cancel.isCancelled())
            assertFalse(live.job.isCompleted)
        }

    @Test
    fun systemStopUsesSameCancellationBoundary() =
        withFixture { f ->
            val live = f.claimLive()
            f.handler.stopTask("turn", false, "FGS_TIMEOUT")
            assertEquals(
                "CANCELLING",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertTrue(live.cancel.isCancelled())
            assertFalse(live.job.isCompleted)
            assertEquals(1, f.approvalCancellations)
        }

    @Test
    fun pauseOnlyDoesNotCancelNonGoalTurn() =
        withFixture { f ->
            val live = f.claimLive()
            f.handler.stopTask("turn", true, "FGS_TIMEOUT")
            assertEquals(
                "WAITING_MODEL",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertFalse(live.cancel.isCancelled())
            assertEquals(0, f.approvalCancellations)
        }

    @Test
    fun cancelledCallerCannotAbortAdmittedDurableStop() =
        withFixture { f ->
            val live = f.claimLive()
            val caller =
                CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    f.handler.cancel(TurnId("turn"))
                }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(
                "CANCELLING",
                f.storage.turns
                    .resolve("turn")
                    .state,
            )
            assertTrue(live.cancel.isCancelled())
            assertFalse(live.job.isCompleted)
        }

    private fun withFixture(block: suspend (Fixture) -> Unit) =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "stop-command-${UUID.randomUUID()}.db"
            val files = File(context.filesDir, name)
            val storage = HelixStorage.open(context, name, files)
            val fixture = Fixture(storage)
            try {
                storage.sessions.create("session", "Stop command fixture", null, null, 1000L)
                startFixtureTurn(
                    storage,
                    fixture.clock,
                    { UUID.randomUUID().toString() },
                    TurnStartSpec("session", "turn", "model", "snapshot", "synthetic question"),
                )
                block(fixture)
            } finally {
                fixture.release.complete(Unit)
                fixture.executor?.join()
                fixture.scope.cancel()
                storage.close()
                context.deleteDatabase(name)
                files.deleteRecursively()
            }
        }

    private class Fixture(
        val storage: HelixStorage,
    ) {
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2000L)
            }
        val engine =
            createRoomTurnEngine(storage, clock, idGenerator = {
                UUID.randomUUID().toString()
            })
        val control = RunControlConfig(AgentMode.CHAT, false, TurnBudgets(maxOutputTokens = 1024))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        var executor: Job? = null
        var onApprovalCancel: () -> Unit = {}
        var approvalCancellations = 0
        private val continuation = GoalContinuationDriver(storage)
        private val userRequests = mutableMapOf<String, Unit>()
        val handler =
            StopTurnCommandHandler(
                engine,
                object : TurnDeliveryControl {
                    override fun handoffOwner(sessionId: String): String? = continuation.handoffOwner(sessionId)

                    override fun disarmTurn(
                        sessionId: String,
                        turnId: String,
                    ) = continuation.disarmTurn(sessionId, turnId)

                    override fun disarm(sessionId: String) = continuation.disarm(sessionId)

                    override fun forgetUserRequests(turnId: String) {
                        userRequests.remove(turnId)
                    }
                },
                PendingTurnApprovalCancellation {
                    approvalCancellations++
                    onApprovalCancel()
                },
            )

        suspend fun claimLive(): com.helix.core.agent.runtime.LiveTurnHandle {
            val entered = CompletableDeferred<Unit>()
            val job =
                scope.launch {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            executor = job
            entered.await()
            return engine.liveExecution.claim("session", "turn", job, control)
        }

        fun setPhase(
            state: TurnState,
            error: String? = null,
        ) {
            val turn = storage.turns.resolve("turn")
            storage.turns.updateState(turn, state, turn.stepCount, if (state.isTerminal) 2000L else null, error)
        }
    }
}
