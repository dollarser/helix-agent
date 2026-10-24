package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnStartSpec
import com.helix.app.test.settleFixtureTerminal
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.turn.TurnRoundCoordinator
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
    @Suppress("LongMethod", "SwallowedException") // The fixture ignores interrupt until actual exit.
    @Test
    fun durableTerminalCannotReleaseAnExecutorOrAnotherConsumersNativeReference() =
        withFixture { storage, turn ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val exited = java.util.concurrent.CountDownLatch(1)
            val cancelled =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            val cleaned =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val resource =
                com.helix.extensions.plugin
                    .NativeResource(Any()) { cleaned.incrementAndGet() }
            val otherConsumer = requireNotNull(resource.borrow())
            val gate =
                com.helix.tools.framework.ExecutionOwnership(
                    object : com.helix.tools.framework.ExecutionOwnership.Store {
                        override fun owners() = emptySet<com.helix.tools.framework.ExecutionOwnership.Owner>()

                        override fun update(
                            expected: Set<com.helix.tools.framework.ExecutionOwnership.Owner>,
                            replacement: Set<com.helix.tools.framework.ExecutionOwnership.Owner>,
                        ): Boolean = error("Ordinary execution must not create a detached runtime owner")
                    },
                )
            val call =
                com.helix.tools.framework.ExecutableToolCall(
                    "original-resource-call",
                    "fixture.read",
                    "1",
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                    com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    object : com.helix.core.model.CancelSignal {
                        override fun isCancelled() = cancelled.get()
                    },
                    "session",
                    "turn",
                )
            val executor =
                object : com.helix.tools.framework.ToolExecutor {
                    override fun execute(
                        call: com.helix.tools.framework.ExecutableToolCall,
                    ): com.helix.tools.framework.ToolExecutorResult =
                        requireNotNull(resource.borrow()).use {
                            assertEquals("original-resource-call", call.toolCallId)
                            assertEquals("turn", call.turnId)
                            entered.countDown()
                            while (release.count != 0L) {
                                try {
                                    release.await()
                                } catch (_: InterruptedException) {
                                    // Actual native IO has not exited.
                                }
                            }
                            com.helix.tools.framework.ToolExecutorResult.Completed(
                                kotlinx.serialization.json.JsonObject(emptyMap()),
                            )
                        }
                }
            val workers =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                val future =
                    workers.submit {
                        try {
                            gate.guard(executor).execute(call)
                        } finally {
                            exited.countDown()
                        }
                    }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                stopIntent(storage)
                cancelled.set(true)
                future.cancel(true)
                turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
                resource.close()
                assertEquals("CANCELLED", storage.turns.resolve("turn").state)
                assertTrue(future.isDone)
                assertEquals(1L, exited.count)
                assertThrows(IllegalStateException::class.java) { gate.acquire(call.toolCallId) }
                assertEquals(com.helix.extensions.plugin.NativeResource.State.DRAINING, resource.state)
                release.countDown()
                assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS))
                requireNotNull(gate.acquire(call.toolCallId)).close()
                assertEquals(0, cleaned.get())
                otherConsumer.close()
                assertEquals(com.helix.extensions.plugin.NativeResource.State.CLOSED, resource.state)
                assertEquals(1, cleaned.get())
            } finally {
                release.countDown()
                workers.shutdown()
                assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                otherConsumer.close()
                resource.close()
            }
        }

    @Test fun durableStopBeforeStreamStartIsCancellationRatherThanInternalFailure() =
        withFixture { storage, turn ->
            stopIntent(storage)
            assertThrows(
                CancellationException::class.java,
            ) { kotlinx.coroutines.runBlocking { turn.beginModelStream() } }
            assertEquals(TurnState.WAITING_MODEL, turn.snapshot().phase)
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals(TurnState.CANCELLED.name, storage.turns.resolve("turn").state)
            assertNull(storage.turns.resolve("turn").errorCode)
            assertEquals(listOf("CANCELLED"), storage.modelCalls.listByTurn("turn").map { it.state })
        }

    @Test fun durableStopBeforeToolBatchDoesNotAdvanceTheTurn() =
        withFixture { storage, turn ->
            kotlinx.coroutines.runBlocking { turn.beginModelStream() }
            stopIntent(storage)
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { turn.beginToolBatch(listOf("tool")) }
            }
            assertTrue(turn.snapshot().batchCalls.isEmpty())
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
        }

    @Test fun stopAfterBatchPreparationPreventsToolStepBeforeDispatcherRegistration() =
        withFixture { storage, turn ->
            kotlinx.coroutines.runBlocking { turn.beginModelStream() }
            kotlinx.coroutines.runBlocking { turn.beginToolBatch(listOf("tool")) }
            // Only the in-memory batch exists; Dispatcher has not registered or executed its calls.
            stopIntent(storage)
            assertThrows(
                CancellationException::class.java,
            ) { kotlinx.coroutines.runBlocking { turn.commitModelToolStep("[]") } }
            val messages = storage.messages.listBySession("session")
            assertTrue(messages.none { it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS })
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals(TurnState.CANCELLED.name, storage.turns.resolve("turn").state)
            assertNull(storage.turns.resolve("turn").errorCode)
            assertEquals("CANCELLED", storage.modelCalls.resolve("model").state)
        }

    @Test fun settledToolResultsSurviveStopWithoutOpeningAnotherModelCall() =
        withFixture { storage, turn ->
            kotlinx.coroutines.runBlocking { turn.beginModelStream() }
            kotlinx.coroutines.runBlocking { turn.beginToolBatch(listOf("tool")) }
            kotlinx.coroutines.runBlocking { turn.commitModelToolStep("[]") }
            turn.settleBatchCall("tool", sideEffectUnknown = false)
            stopIntent(storage)
            val result = TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, "settled result")
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { turn.openNextModelCall(listOf(result), "next-call") }
            }
            val results =
                storage.messages.listBySession("session").filter { it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT }
            assertEquals(listOf("settled result"), results.map(storage.messages::readContent))
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("COMPLETED", storage.modelCalls.resolve("model").state)
            assertNull(storage.turns.resolve("turn").errorCode)
        }

    @Test fun durableStopPreventsCompactionCheckpointCommit() =
        withFixture { storage, turn ->
            val stream = kotlinx.coroutines.runBlocking { turn.beginModelStream(compacting = true) }
            stream.apply(ModelEvent.TextDelta("summary"))
            stream.apply(ModelEvent.Completed("stop"))
            val request =
                TurnContextRequest(
                    "fixture",
                    listOf(ModelMessage(ModelRole.USER, "question")),
                    emptyList(),
                    512,
                    ReasoningEffort.OFF,
                )
            val plan = ContextCompactionPlan(0, request.modelRequest(), request)
            stopIntent(storage)
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { turn.commitCompaction(plan, "next-call") }
            }
            assertNull(ContextCompaction.checkpoint(storage, storage.messages.listBySession("session")))
            assertEquals(listOf("model"), storage.modelCalls.listByTurn("turn").map { it.id })
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("CANCELLED", storage.modelCalls.resolve("model").state)
        }

    private fun stopIntent(storage: HelixStorage) {
        storage.withTransaction {
            val row = storage.turns.resolve("turn")
            storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
        }
    }

    private fun withFixture(block: (HelixStorage, TurnRoundCoordinator) -> Unit) {
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
                startFixtureTurn(
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
