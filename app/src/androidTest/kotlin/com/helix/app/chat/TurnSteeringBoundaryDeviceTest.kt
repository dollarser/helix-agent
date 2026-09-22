package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ResponseInputBoundary
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnMessageDraft
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnSteeringDraft
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real Room transactions cover the final-answer/input and complete-tool-batch boundaries. */
@RunWith(AndroidJUnit4::class)
class TurnSteeringBoundaryDeviceTest {
    @Test
    fun acceptedAfterFinalSnapshotRequiresRecheckAndContinuesTheSameTurn() =
        fixture { storage, coordinator ->
            coordinator.beginModelStream().apply(ModelEvent.TextDelta("first answer"))
            val draft = accept(storage)
            assertEquals(ResponseInputBoundary.RECHECK, coordinator.completeResponseOrContinue(null, "next"))
            assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve("turn").state)
            assertEquals(ResponseInputBoundary.CONTINUED, coordinator.completeResponseOrContinue(draft, "next"))
            assertEquals("turn", storage.sessionInputs.get("input")?.consumedTurnId)
            assertEquals(2, coordinator.snapshot().modelStep)
            assertEquals("next", coordinator.snapshot().modelCallId)
            assertEquals(
                listOf("initial", "first answer", "change direction"),
                storage.messages.listBySession("session").map(storage.messages::readContent),
            )
            assertNull(storage.sessionInputs.get("input")?.requestModelCallId)
        }

    @Test
    fun editedSnapshotCannotConsumeStaleText() =
        fixture { storage, coordinator ->
            val stale = accept(storage)
            assertTrue(storage.sessionInputs.editPending("input", stale.input.revision, spec("updated", 1)))
            assertFalse(coordinator.appendSteeringBeforeRequest(stale))
            val history = storage.messages.listBySession("session").map(storage.messages::readContent)
            assertEquals(listOf("initial"), history)
            val current = requireNotNull(storage.sessionInputs.get("input"))
            val updated = TurnSteeringDraft(current, "updated", emptyList())
            assertTrue(coordinator.appendSteeringBeforeRequest(updated))
            assertFalse(coordinator.appendSteeringBeforeRequest(stale))
            assertEquals(2, storage.messages.listBySession("session").size)
        }

    @Test
    fun cancellationWinsWithoutAppendingOrOpeningAnotherRequest() =
        fixture { storage, coordinator ->
            coordinator.beginModelStream().apply(ModelEvent.TextDelta("partial"))
            val draft = accept(storage)
            storage.withTransaction {
                storage.turns.updateState(storage.turns.resolve("turn"), TurnState.CANCELLING, 1, null, null)
                storage.sessionInputs.parkSessionInputs("session", "USER_STOPPED", 3_000)
            }
            assertEquals(ResponseInputBoundary.CANCELLED, coordinator.completeResponseOrContinue(draft, "never"))
            assertEquals(TurnState.CANCELLING.name, storage.turns.resolve("turn").state)
            assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("input")?.state)
            assertEquals(1, storage.messages.listBySession("session").count { it.role == "USER" })
            assertEquals("model", coordinator.snapshot().modelCallId)
        }

    @Test
    fun steeringCannotSplitAnUnsettledToolBatch() =
        fixture { storage, coordinator ->
            coordinator.beginModelStream()
            coordinator.beginToolBatch(listOf("a", "b"))
            coordinator.commitModelToolStep("[]")
            val draft = accept(storage)
            coordinator.settleBatchCall("a", false)
            assertThrows(IllegalArgumentException::class.java) { coordinator.appendSteeringBeforeRequest(draft) }
            coordinator.settleBatchCall("b", false)
            coordinator.openNextModelCall(
                listOf(
                    TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, "result-a"),
                    TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, "result-b"),
                ),
                "next",
            )
            assertTrue(coordinator.appendSteeringBeforeRequest(draft))
            assertEquals(
                listOf("TOOL", "TOOL", "USER"),
                storage.messages
                    .listBySession("session")
                    .takeLast(3)
                    .map { it.role },
            )
        }

    @Test
    fun requestReceiptOnlyBindsActuallySelectedMessages() =
        fixture { storage, coordinator ->
            assertTrue(coordinator.appendSteeringBeforeRequest(accept(storage)))
            coordinator.beginModelStream()
            coordinator.recordInputRequestStarted(emptySet())
            assertNull(storage.sessionInputs.get("input")?.requestModelCallId)
            val messageId = requireNotNull(storage.sessionInputs.get("input")?.messageId)
            coordinator.recordInputRequestStarted(setOf(messageId))
            coordinator.recordInputRequestStarted(setOf(messageId))
            assertEquals("model", storage.sessionInputs.get("input")?.requestModelCallId)
        }

    @Test
    fun requestReceiptsCoverMoreInputsThanPendingQueueCapacity() =
        fixture { storage, coordinator ->
            repeat(33) { index ->
                val request = spec().copy(inputId = "input-$index")
                val accepted = storage.sessionInputs.accept(request) as SessionInputAcceptResult.Accepted
                assertTrue(
                    coordinator.appendSteeringBeforeRequest(
                        TurnSteeringDraft(accepted.record, request.text, emptyList()),
                    ),
                )
            }
            val selected =
                storage.messages
                    .listBySession("session")
                    .map { it.id }
                    .toSet()
            coordinator.beginModelStream()
            coordinator.recordInputRequestStarted(selected)
            val inputs = storage.sessionInputs.appendedForMessages("turn", selected)
            assertEquals(33, inputs.size)
            assertTrue(inputs.all { it.requestModelCallId == "model" })
            assertTrue(storage.sessionInputs.appendedForMessages("other-turn", selected).isEmpty())
            coordinator.recordInputRequestStarted(selected)
            assertEquals(inputs, storage.sessionInputs.appendedForMessages("turn", selected))
        }

    private fun accept(storage: HelixStorage): TurnSteeringDraft {
        val accepted = storage.sessionInputs.accept(spec()) as SessionInputAcceptResult.Accepted
        return TurnSteeringDraft(accepted.record, "change direction", emptyList())
    }

    private fun spec(
        text: String = "change direction",
        revision: Long = 0,
    ) = SessionInputSpec(
        "input",
        "session",
        SessionInputDelivery.STEER,
        "turn",
        revision,
        text,
        emptyList(),
        InputConfiguration("provider", "model", "CHAT", "fingerprint"),
        2_000,
    )

    private fun fixture(block: (HelixStorage, TurnCoordinator) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storage = HelixStorage.open(context, "steer-boundary-$suffix.db", File(context.filesDir, "steer-$suffix"))
        try {
            storage.sessions.create("session", "Steering", null, null, 1_000)
            var id = 0
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    object : Clock {
                        override fun now(): Instant = Instant.ofEpochMilli(2_000)
                    },
                    { "generated-${id++}" },
                    TurnStartSpec("session", "turn", "model", "snapshot", "initial"),
                )
            block(storage, coordinator)
        } finally {
            storage.close()
        }
    }
}
