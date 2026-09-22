package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatContextRequest
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnMessageDraft
import com.helix.app.agent.TurnStartSpec
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Deterministically pause between the durable stop intent and the later coroutine cancel signal. */
class TurnCancellationRaceDeviceTest {
    @Test fun durableStopBeforeStreamStartIsCancellationRatherThanInternalFailure() =
        withFixture { storage, turn ->
            stopIntent(storage)
            assertThrows(CancellationException::class.java) { turn.beginModelStream() }
            assertEquals(TurnState.WAITING_MODEL, turn.snapshot().phase)
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals(TurnState.CANCELLED.name, storage.turns.resolve("turn").state)
            assertNull(storage.turns.resolve("turn").errorCode)
            assertEquals(listOf("CANCELLED"), storage.modelCalls.listByTurn("turn").map { it.state })
        }

    @Test fun durableStopBeforeToolBatchDoesNotAdvanceTheTurn() =
        withFixture { storage, turn ->
            turn.beginModelStream()
            stopIntent(storage)
            assertThrows(CancellationException::class.java) { turn.beginToolBatch(listOf("tool")) }
            assertTrue(turn.snapshot().batchCalls.isEmpty())
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
        }

    @Test fun stopAfterBatchPreparationPreventsToolStepBeforeDispatcherRegistration() =
        withFixture { storage, turn ->
            turn.beginModelStream()
            turn.beginToolBatch(listOf("tool"))
            // Only the in-memory batch exists; Dispatcher has not registered or executed its calls.
            stopIntent(storage)
            assertThrows(CancellationException::class.java) { turn.commitModelToolStep("[]") }
            val messages = storage.messages.listBySession("session")
            assertTrue(messages.none { it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS })
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals(TurnState.CANCELLED.name, storage.turns.resolve("turn").state)
            assertNull(storage.turns.resolve("turn").errorCode)
            assertEquals("CANCELLED", storage.modelCalls.resolve("model").state)
        }

    @Test fun settledToolResultsSurviveStopWithoutOpeningAnotherModelCall() =
        withFixture { storage, turn ->
            turn.beginModelStream()
            turn.beginToolBatch(listOf("tool"))
            turn.commitModelToolStep("[]")
            turn.settleBatchCall("tool", sideEffectUnknown = false)
            stopIntent(storage)
            val result = TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, "settled result")
            assertThrows(CancellationException::class.java) { turn.openNextModelCall(listOf(result), "next-call") }
            val results =
                storage.messages.listBySession("session").filter { it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT }
            assertEquals(listOf("settled result"), results.map(storage.messages::readContent))
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("COMPLETED", storage.modelCalls.resolve("model").state)
            assertNull(storage.turns.resolve("turn").errorCode)
        }

    @Test fun durableStopPreventsCompactionCheckpointCommit() =
        withFixture { storage, turn ->
            val stream = turn.beginModelStream(compacting = true)
            stream.apply(ModelEvent.TextDelta("summary"))
            stream.apply(ModelEvent.Completed("stop"))
            val request =
                ChatContextRequest(
                    "fixture",
                    listOf(ModelMessage(ModelRole.USER, "question")),
                    emptyList(),
                    512,
                    ReasoningEffort.OFF,
                )
            val plan = ContextCompaction.Plan(0, request.modelRequest(), request)
            stopIntent(storage)
            assertThrows(CancellationException::class.java) { turn.commitCompaction(plan, "next-call") }
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("session")))
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("CANCELLED", storage.modelCalls.resolve("model").state)
        }

    private fun stopIntent(storage: HelixStorage) {
        storage.withTransaction {
            val row = storage.turns.resolve("turn")
            storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
        }
    }

    private fun withFixture(block: (HelixStorage, TurnCoordinator) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "turn-stop-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, files)
        try {
            storage.sessions.create("session", "Cancellation race", null, null, 1000L)
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(2000L)
                }
            val turn =
                TurnCoordinator.start(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                    TurnStartSpec("session", "turn", "model", "snapshot", "question"),
                )
            block(storage, turn)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }
}
