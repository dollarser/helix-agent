package com.helix.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.AgentTurnStore
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.TerminalCommitResult
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Executes the same domain contract through the production Room adapter, not a fake transaction. */
class TurnCommitStoreDeviceTest {
    @Test fun wrongSessionCannotAppendOrCloseAnyRow() =
        fixture { storage, turn, store ->
            storage.sessions.create("other", "Other", null, null, 1_000)
            val before = storage.messages.listBySession("s")
            val original = command(turn)
            val forged = original.copy(checkpoint = original.checkpoint.copy(sessionId = "other"))
            val result = store.commitTerminal(forged)
            assertEquals(TerminalCommitResult.Conflict("TURN_SESSION_MISMATCH"), result)
            assertEquals(before, storage.messages.listBySession("s"))
            assertTrue(storage.messages.listBySession("other").isEmpty())
            assertUnchanged(storage)
        }

    @Test fun anotherTurnsModelCallCannotBeOverwritten() =
        fixture { storage, turn, store ->
            storage.sessions.create("other", "Other", null, null, 1_000)
            val otherSpec = TurnStartSpec("other", "other-t", "other-m", "snapshot", "other")
            TurnCoordinator.start(storage, CLOCK, { UUID.randomUUID().toString() }, otherSpec)
            val original = command(turn)
            val forged = original.copy(checkpoint = original.checkpoint.copy(modelCallId = "other-m"))
            val result = store.commitTerminal(forged)
            assertEquals(TerminalCommitResult.Conflict("MODEL_CALL_TURN_MISMATCH"), result)
            assertEquals("RUNNING", storage.modelCalls.resolve("other-m").state)
            assertUnchanged(storage)
        }

    @Test fun staleAndFutureStepsCannotTerminalize() =
        fixture { storage, turn, store ->
            val original = command(turn)
            val futureStep = original.copy(checkpoint = original.checkpoint.copy(modelStep = 2))
            assertEquals(TerminalCommitResult.Conflict("TURN_STEP_CONFLICT"), store.commitTerminal(futureStep))
            storage.turns.updateState(storage.turns.resolve("t"), TurnState.RECEIVING_MODEL, 2, null, null)
            assertEquals(TerminalCommitResult.Conflict("TURN_STEP_CONFLICT"), store.commitTerminal(original))
            assertEquals(2, storage.turns.resolve("t").stepCount)
            assertEquals("RUNNING", storage.modelCalls.resolve("m").state)
        }

    @Test fun wrongPhaseIsNotAStaleOwnerBypass() =
        fixture { storage, turn, store ->
            val original = command(turn)
            val wrongPhase = original.copy(checkpoint = original.checkpoint.copy(phase = TurnState.WAITING_MODEL))
            assertEquals(TerminalCommitResult.Conflict("TURN_PHASE_CONFLICT"), store.commitTerminal(wrongPhase))
            assertUnchanged(storage)
        }

    @Test fun conflictingCallBlocksCommit() =
        fixture { storage, turn, store ->
            storage.modelCalls.append("new-m", "t", "snapshot", "RUNNING")
            val expected = TerminalCommitResult.Conflict("MODEL_CALL_OWNER_CONFLICT")
            assertEquals(expected, store.commitTerminal(command(turn)))
            assertUnchanged(storage)
        }

    @Test fun duplicateRetainsReceiptAndText() =
        fixture { storage, turn, store ->
            val original = command(turn)
            assertEquals(TerminalCommitResult.Applied(original.outcome), store.commitTerminal(original))
            val messages = storage.messages.listBySession("s")
            val conflictingRetry = original.copy(outcome = ModelStreamTerminal(TurnState.FAILED, "LATE_ERROR"))
            val expected = TerminalCommitResult.AlreadyApplied(original.outcome)
            assertEquals(expected, store.commitTerminal(conflictingRetry))
            assertEquals(messages, storage.messages.listBySession("s"))
            assertEquals("COMPLETED", storage.modelCalls.resolve("m").state)
        }

    @Test fun durableStopWinsOverLateSuccess() =
        fixture { storage, turn, store ->
            val current = storage.turns.resolve("t")
            storage.turns.updateState(current, TurnState.CANCELLING, current.stepCount, null, null)
            assertEquals(
                TerminalCommitResult.Applied(ModelStreamTerminal(TurnState.CANCELLED, null)),
                store.commitTerminal(command(turn)),
            )
            assertEquals("CANCELLED", storage.modelCalls.resolve("m").state)
            assertEquals(TurnState.CANCELLED.name, storage.turns.resolve("t").state)
        }

    @Test fun failedCommitRollsBackTextAndState() =
        fixture { storage, turn, _ ->
            val before = storage.messages.listBySession("s")
            val invalidClock =
                object : Clock {
                    override fun now(): Instant = Instant.EPOCH
                }
            val store: AgentTurnStore = TurnSettlement(storage, invalidClock) { UUID.randomUUID().toString() }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.commitTerminal(command(turn)) }
            }
            assertEquals(before, storage.messages.listBySession("s"))
            assertUnchanged(storage)
        }

    @Test fun nextStepPrecedesStream() =
        fixture { storage, turn, store ->
            turn.beginToolBatch(listOf("call"))
            turn.commitModelToolStep("[]")
            turn.settleBatchCall("call", false)
            turn.openNextModelCall(emptyList(), "next")
            assertEquals(turn.snapshot().modelStep, storage.turns.resolve("t").stepCount)
            assertEquals(2, storage.turns.resolve("t").stepCount)
            val stopped = ModelStreamTerminal(TurnState.CANCELLED, null)
            val command = TerminalCommitCommand(turn.terminalCheckpoint(), stopped)
            assertEquals(TerminalCommitResult.Applied(stopped), store.commitTerminal(command))
            assertEquals("COMPLETED", storage.modelCalls.resolve("m").state)
            assertEquals("CANCELLED", storage.modelCalls.resolve("next").state)
        }

    private fun command(turn: TurnCoordinator) =
        TerminalCommitCommand(turn.terminalCheckpoint(), ModelStreamTerminal(TurnState.COMPLETED, null))

    private fun assertUnchanged(storage: HelixStorage) {
        assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve("t").state)
        assertEquals("RUNNING", storage.modelCalls.resolve("m").state)
    }

    private fun fixture(block: suspend (HelixStorage, TurnCoordinator, AgentTurnStore) -> Unit) =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "turn-commit-${UUID.randomUUID()}.db"
            val files = File(context.filesDir, name)
            val storage = HelixStorage.open(context, name, files)
            try {
                storage.sessions.create("s", "Commit", null, null, 1_000)
                val spec = TurnStartSpec("s", "t", "m", "snapshot", "question")
                val turn = TurnCoordinator.start(storage, CLOCK, { UUID.randomUUID().toString() }, spec)
                assertEquals(turn.snapshot().modelStep, storage.turns.resolve("t").stepCount)
                turn.beginModelStream().apply(ModelEvent.TextDelta("partial"))
                block(storage, turn, TurnSettlement(storage, CLOCK) { UUID.randomUUID().toString() })
            } finally {
                storage.close()
                context.deleteDatabase(name)
                files.deleteRecursively()
            }
        }

    companion object {
        private val CLOCK =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2_000)
            }
    }
}
