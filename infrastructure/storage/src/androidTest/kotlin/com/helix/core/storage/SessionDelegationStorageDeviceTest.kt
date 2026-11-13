package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.ModelDelegationOrigin
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.SessionInputAcceptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SessionDelegationStorageDeviceTest {
    @Test fun revocationAndRegrantCannotAppendAnOlderAcceptedDelegation() =
        fixture { storage ->
            val input = accepted(storage)
            storage.sessionAccess.setGrant("source", "target", false, false, false)
            assertFalse(storage.sessionInputs.delegationAllowed(input))
            storage.sessionAccess.setGrant("source", "target", false, false, true)
            assertFalse(storage.sessionInputs.delegationAllowed(input))
            assertFalse(append(storage, input.inputId))
            assertNull(storage.turns.find("target-turn"))
            assertNull(storage.messages.find("target-message"))
        }

    @Test fun queuedOriginCannotBeEditedOrPromoted() =
        fixture { storage ->
            val input = accepted(storage)
            storage.turns.start("busy", "target", 1)
            assertFalse(storage.sessionInputs.steerPending(input.inputId, 0, "busy", 2))
            val edited = spec().copy(revision = 1, text = "changed")
            assertFalse(storage.sessionInputs.editPending(input.inputId, 0, edited))
            assertTrue(append(storage, input.inputId))
            assertTrue(storage.sessionInputs.isDelegatedTurn("target-turn"))
            assertFalse(storage.sessionInputs.delegationTargetAllowed("target-turn", "source"))
            assertTrue(storage.sessionInputs.delegationTargetAllowed("target-turn", "third-target"))
            assertFalse(storage.sessionInputs.isDelegatedTurn("source-turn"))
            assertTrue(storage.sessionInputs.skillActivations("target", "target-turn").isEmpty())
            storage.sessionAccess.setGrant("source", "target", false, false, false)
            assertEquals(
                "APPENDED",
                storage.sessionInputs
                    .get(input.inputId)!!
                    .state.name,
            )
            assertEquals("CREATED", storage.turns.find("target-turn")!!.state)
        }

    @Test fun deletedTargetRetainsDedupTombstone() =
        fixture { storage ->
            accepted(storage)
            storage.deleteSessionPermanently("target")
            assertThrows(IllegalArgumentException::class.java) {
                storage.sessionInputs.delegationReceipt("source", "key")
            }
            assertNotNull(storage.database.sessionInputDao().delegationReceipt("source", "key"))
        }

    @Test fun originAndReceiptSurviveDatabaseReopenWithoutReexecuting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delegation-reopen-${UUID.randomUUID()}.db"
        val dir = File(context.cacheDir, name)
        var storage = HelixStorage.open(context, name, dir)
        try {
            setup(storage)
            val original = accepted(storage)
            storage.close()
            storage = HelixStorage.open(context, name, dir)
            val recovered = storage.sessionInputs.delegationReceipt("source", "key")!!
            assertEquals(original, recovered)
            assertEquals(spec().delegation, recovered.delegation)
            assertEquals("MODEL_DELEGATION", recovered.origin.name)
            assertTrue(storage.turns.listBySession("target").isEmpty())
        } finally {
            storage.close()
            context.deleteDatabase(name)
            dir.deleteRecursively()
        }
    }

    @Test fun unknownArchivedAndWrongCallerCannotCreateAcceptedDelegation() =
        fixture { storage ->
            val unknown = storage.sessionInputs.accept(spec().copy(sessionId = "unknown"))
            assertTrue(unknown is SessionInputAcceptResult.Rejected)
            storage.sessions.archive("target", 2)
            assertTrue(storage.sessionInputs.accept(spec()) is SessionInputAcceptResult.Rejected)
            storage.sessions.restore("target")
            storage.database.toolCallDao().updateState("source-call", "COMPLETED")
            assertTrue(storage.sessionInputs.accept(spec()) is SessionInputAcceptResult.Rejected)
        }

    @Test fun steerIsBoundToExactActiveTurnAndNeverFallsBackToQueue() =
        fixture { storage ->
            val turn = storage.turns.start("active-target", "target", 2)
            storage.toolCalls.append(
                "steer-call",
                "source-turn",
                "wire-steer",
                "session.steer",
                "1",
                "{}",
                "RUNNING",
            )
            val steer =
                spec().copy(
                    inputId = "steer-input",
                    delivery = SessionInputDelivery.STEER,
                    expectedTurnId = turn.id,
                    delegation = ModelDelegationOrigin("source", "source-turn", "steer-call", "steer-key", 1),
                )
            val missing = storage.sessionInputs.accept(steer.copy(expectedTurnId = "missing"))
            assertTrue(missing is SessionInputAcceptResult.Rejected)
            val original = (storage.sessionInputs.accept(steer) as SessionInputAcceptResult.Accepted).record
            listOf(
                com.helix.core.model.TurnState.BUILDING_CONTEXT,
                com.helix.core.model.TurnState.WAITING_MODEL,
                com.helix.core.model.TurnState.RECEIVING_MODEL,
                com.helix.core.model.TurnState.COMPLETED,
            ).fold(turn) { current, state ->
                storage.turns.updateState(current, state, 0, if (state.isTerminal) 4 else null, null)
            }
            assertFalse(storage.sessionInputs.requeueAfterTargetFinished(original.inputId, 0, 5))
            assertEquals(original, storage.sessionInputs.delegationReceipt("source", "steer-key"))
            assertEquals(SessionInputDelivery.STEER, storage.sessionInputs.get(original.inputId)!!.delivery)
            val another = steer.copy(inputId = "late", delegation = steer.delegation!!.copy(requestKey = "late"))
            assertTrue(storage.sessionInputs.accept(another) is SessionInputAcceptResult.Rejected)
        }

    @Test fun steerAppendRevocationDoesNotChangeTheTargetTurnOrAppendMessage() =
        fixture { storage ->
            val turn = storage.turns.start("active-target", "target", 2)
            storage.toolCalls.append(
                "steer-call",
                "source-turn",
                "wire-steer",
                "session.steer",
                "1",
                "{}",
                "RUNNING",
            )
            val steer =
                spec().copy(
                    inputId = "steer-input",
                    delivery = SessionInputDelivery.STEER,
                    expectedTurnId = turn.id,
                    delegation = ModelDelegationOrigin("source", "source-turn", "steer-call", "steer-key", 1),
                )
            val original = (storage.sessionInputs.accept(steer) as SessionInputAcceptResult.Accepted).record
            storage.sessionAccess.setGrant("source", "target", false, false, false)
            assertThrows(LostRace::class.java) {
                storage.withTransaction {
                    storage.messages.append("steer-message", "target", turn.id, "USER", "TEXT", "task")
                    val appended = storage.sessionInputs.markAppended(original.inputId, 0, turn.id, "steer-message", 5)
                    if (!appended) throw LostRace()
                }
            }
            assertNull(storage.messages.find("steer-message"))
            assertEquals(turn, storage.turns.find(turn.id))
            assertFalse(storage.sessionInputs.isDelegatedTurn(turn.id))
        }

    @Test fun historyExposesTypedOriginWithoutChangingRawContentHash() =
        fixture { storage ->
            accepted(storage)
            assertTrue(append(storage, "input"))
            val turn = storage.turns.resolve("target-turn")
            listOf(
                com.helix.core.model.TurnState.BUILDING_CONTEXT,
                com.helix.core.model.TurnState.WAITING_MODEL,
                com.helix.core.model.TurnState.RECEIVING_MODEL,
                com.helix.core.model.TurnState.RUNNING_TOOL,
            ).fold(turn) { current, state -> storage.turns.updateState(current, state, 0, null, null) }
            storage.database.toolCallDao().insert(
                ToolCallEntity(
                    "history-call",
                    turn.id,
                    "history-wire",
                    "session.history",
                    "1",
                    "{}",
                    "c".repeat(64),
                    "RUNNING",
                ),
            )
            val caller =
                com.helix.core.storage.repository.SessionHistoryCaller(
                    "target",
                    turn.id,
                    "history-call",
                    "session.history",
                )
            val page = storage.sessionHistory.page(caller, Long.MAX_VALUE, 20)
            assertEquals(spec().delegation, page.delegations["target-message"])
            val content = storage.sessionHistory.read(caller, "target-message", null)
            assertEquals(spec().delegation, content.delegation)
            assertEquals("task", content.content)
            val raw = storage.messages.resolve("target-message").contentRef!!
            assertEquals(
                com.helix.core.storage.content.ContentRef
                    .parse(raw)
                    .sha256,
                content.sha256,
            )
        }

    @Test fun corruptCyclicProvenanceFailsClosedForFreshTarget() =
        fixture { storage ->
            accepted(storage)
            assertTrue(append(storage, "input"))
            val dao = storage.database.sessionInputDao()
            val row = dao.byId("input")!!
            dao.update(row.copy(delegationSourceSessionId = "target", delegationSourceTurnId = "target-turn"))
            assertFalse(storage.sessionInputs.delegationTargetAllowed("target-turn", "third-target"))
        }

    private fun setup(storage: HelixStorage) {
        storage.sessions.create("source", "Source", null, null, 0)
        storage.sessions.create("target", "Target", null, null, 0)
        storage.database.turnDao().insert(TurnEntity("source-turn", "source", "RUNNING_TOOL", 0, 0, null, null))
        storage.database.toolCallDao().insert(
            ToolCallEntity("source-call", "source-turn", "wire", "session.queue", "1", "{}", "a".repeat(64), "RUNNING"),
        )
        storage.sessionAccess.setGrant("source", "target", false, false, true)
    }

    private fun spec() =
        SessionInputSpec(
            "input",
            "target",
            SessionInputDelivery.QUEUE,
            null,
            0,
            "task",
            emptyList(),
            InputConfiguration("provider", "model", "ACT", "fingerprint"),
            1,
            delegation = ModelDelegationOrigin("source", "source-turn", "source-call", "key", 1),
        )

    private fun accepted(storage: HelixStorage) =
        (storage.sessionInputs.accept(spec()) as SessionInputAcceptResult.Accepted).record

    private fun append(
        storage: HelixStorage,
        input: String,
    ): Boolean =
        try {
            storage.withTransaction {
                storage.turns.start("target-turn", "target", 2, input)
                storage.messages.append("target-message", "target", "target-turn", "USER", "TEXT", "task")
                if (!storage.sessionInputs.markAppended(input, 0, "target-turn", "target-message", 3)) throw LostRace()
            }
            true
        } catch (_: LostRace) {
            false
        }

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delegation-${UUID.randomUUID()}.db"
        val dir = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, dir)
        try {
            setup(storage)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            dir.deleteRecursively()
        }
    }

    private class LostRace : RuntimeException()
}
