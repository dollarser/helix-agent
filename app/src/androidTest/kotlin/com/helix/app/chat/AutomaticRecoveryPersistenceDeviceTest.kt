package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.agent.AutomaticRecoverySettlement
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.RoomInputRecoveryStore
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.application.recovery.AutomaticInputRecovery
import com.helix.feature.conversation.application.recovery.AutomaticTurnRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class AutomaticRecoveryPersistenceDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(100)
        }

    @Test fun queueRecoveryIsDurableAcrossCoordinatorRecreation() =
        runBlocking {
            withStorage { storage ->
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                var checks = 0

                fun recovery() =
                    AutomaticInputRecovery(RoomInputRecoveryStore(storage, Any()), clock, {
                        checks++
                        true
                    }, { "Ended" })
                recovery().recover("session")
                assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("input")?.state)
                storage.sessionInputs.parkSessionInputs("session", "INPUT_DELIVERY_FAILED", 3)
                recovery().recover("session")
                recovery().recover("session")
                assertEquals(1, checks)
                val failed = requireNotNull(storage.sessionInputs.get("input"))
                assertEquals(SessionInputState.FAILED, failed.state)
                assertEquals("Original request", storage.sessionInputs.readText(failed))
                assertEquals(1, storage.messages.listBySession("session").count { it.kind == "INPUT_RECOVERY_NOTICE" })
            }
        }

    @Test fun startupWakeCandidatesKeepPendingAndParkedInputsWithoutWakingSettledOrArchivedHistory() =
        runBlocking {
            withStorage { storage ->
                assertTrue(storage.sessionInputs.sessionsWithPending().isEmpty())
                for (id in listOf("parked", "withdrawn", "failed", "archived", "empty")) {
                    storage.sessions.create(id, "Synthetic $id", null, null, 0)
                }
                for (id in listOf("session", "parked", "withdrawn", "failed", "archived")) {
                    storage.sessionInputs.accept(input().copy(inputId = "$id-input", sessionId = id))
                }
                storage.sessionInputs.accept(input().copy(inputId = "second-input"))
                storage.sessionInputs.parkSessionInputs("parked", "PROCESS_INTERRUPTED", 2)
                assertTrue(storage.sessionInputs.withdrawPending("withdrawn-input", 0, 2))
                assertTrue(storage.sessionInputs.failPending("failed-input", 0, "SYNTHETIC_FAILURE", 2))
                storage.sessions.archive("archived", 2)
                assertEquals(listOf("parked", "session"), storage.sessionInputs.sessionsWithPending())
                storage.sessions.restore("archived")
                assertEquals(listOf("archived", "parked", "session"), storage.sessionInputs.sessionsWithPending())
                storage.sessionInputs.parkSessionInputs("session", "USER_STOP", 3)
                // Candidate selection does not grant execution: stopped and attention states stay parked.
                assertEquals(listOf("archived", "parked", "session"), storage.sessionInputs.sessionsWithPending())
                assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("second-input")?.state)
            }
        }

    @Test fun userStopDoesNotReleaseParkedInputs() =
        runBlocking {
            withStorage { storage ->
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                val turn = storage.turns.start("stopped", "session", 1)
                storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 2, "USER_STOP")
                AutomaticInputRecovery(RoomInputRecoveryStore(storage, Any()), clock, {
                    error("must not revalidate after Stop")
                }, { "Ended" })
                    .recover("session")
                assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("input")?.state)
            }
        }

    @Test fun stopDuringInputRevalidationCannotResumeOrFailTheParkedInput() =
        runBlocking {
            withStorage { storage ->
                val turn = storage.turns.start("parent", "session", 1)
                storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 2, null)
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                val before = requireNotNull(storage.sessionInputs.get("input"))
                val engine = createRoomTurnEngine(storage, clock, idGenerator = { UUID.randomUUID().toString() })
                AutomaticInputRecovery(RoomInputRecoveryStore(storage, engine.executionGate), clock, {
                    engine.requestCancel("parent", "session", false, false, "USER_STOP")
                    true
                }, { "Ended" }).recover("session")
                assertEquals("USER_STOP", storage.turns.resolve("parent").errorCode)
                assertEquals(before, storage.sessionInputs.get("input"))
                assertTrue(storage.messages.listBySession("session").isEmpty())
            }
        }

    @Test fun inputEditDuringRevalidationCannotBeFailedUsingItsNewRevision() =
        runBlocking {
            withStorage { storage ->
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                val original = requireNotNull(storage.sessionInputs.get("input"))
                AutomaticInputRecovery(RoomInputRecoveryStore(storage, Any()), clock, {
                    assertTrue(
                        storage.sessionInputs.editPending(
                            "input",
                            original.revision,
                            input().copy(revision = original.revision + 1, text = "User replacement"),
                        ),
                    )
                    false
                }, { "Ended" }).recover("session")
                val current = requireNotNull(storage.sessionInputs.get("input"))
                assertEquals(original.revision + 1, current.revision)
                assertEquals(SessionInputState.NEEDS_ATTENTION, current.state)
                assertEquals("User replacement", storage.sessionInputs.readText(current))
                assertTrue(storage.messages.listBySession("session").isEmpty())
            }
        }

    @Test fun archivedSessionDuringRevalidationCannotResumeOrFailItsInput() =
        runBlocking {
            withStorage { storage ->
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                val before = storage.sessionInputs.get("input")
                AutomaticInputRecovery(RoomInputRecoveryStore(storage, Any()), clock, {
                    storage.sessions.archive("session", 3)
                    true
                }, { "Ended" }).recover("session")
                assertEquals(before, storage.sessionInputs.get("input"))
                assertTrue(storage.messages.listBySession("session").isEmpty())
            }
        }

    @Test fun missingRuntimeEndsOnceWithoutModelOrExecutorReplay() =
        runBlocking {
            withStorage { storage ->
                val turn = storage.turns.start("parent", "session", 1)
                storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 2, null)
                val settlement = AutomaticRecoverySettlement(storage, clock)
                val recovery =
                    AutomaticTurnRecovery(
                        com.helix.app.infrastructure.agent
                            .RoomTurnRecoveryStore(storage, Any(), settlement::finish),
                        clock,
                        { _, _ -> error("no runtime") },
                        { throw it },
                    )
                repeat(2) { recovery.recover("parent") }
                assertEquals(1, storage.turns.listBySession("session").size)
                assertEquals(
                    1,
                    storage.messages.listBySession("session").count {
                        it.kind ==
                            com.helix.core.agent.TurnInterruptionNotice.KIND
                    },
                )
                assertTrue(storage.auditEvents.listByCorrelation("session").any { it.type == "recovery.ended" })
            }
        }

    @Test fun stopDuringUnknownPersistsWithoutPretendingEffectsWereCancelled() =
        runBlocking {
            withStorage { storage ->
                var turn = storage.turns.start("unknown", "session", 1)
                listOf(
                    TurnState.BUILDING_CONTEXT,
                    TurnState.WAITING_MODEL,
                    TurnState.RECEIVING_MODEL,
                    TurnState.RUNNING_TOOL,
                    TurnState.NEEDS_REVIEW,
                ).forEach {
                    turn = storage.turns.updateState(turn, it, 0, null, null)
                }
                val engine =
                    createRoomTurnEngine(storage, clock, idGenerator = {
                        UUID.randomUUID().toString()
                    })
                engine.requestCancel(turn.id, "session", false, false, "USER_STOP")
                val stopped = storage.turns.resolve(turn.id)
                assertEquals("NEEDS_REVIEW", stopped.state)
                assertEquals("USER_STOP", stopped.errorCode)
                AutomaticRecoverySettlement(storage, clock).finish(turn.id, "RECOVERY_INSPECTION_FINISHED", true)
                assertEquals(
                    0,
                    storage.messages.byTurn(turn.id).count {
                        it.kind == com.helix.core.agent.TurnInterruptionNotice.KIND
                    },
                )
                assertTrue(
                    !com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
                        .eligible(stopped),
                )
            }
        }

    private fun input() =
        SessionInputSpec(
            "input",
            "session",
            SessionInputDelivery.QUEUE,
            null,
            0,
            "Original request",
            emptyList(),
            InputConfiguration("provider", "model", "ACT", "config"),
            1,
        )

    private suspend fun withStorage(block: suspend (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "automatic-recovery-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(directory, "content"))
        try {
            storage.sessions.create("session", "Recovery", null, null, 0)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
