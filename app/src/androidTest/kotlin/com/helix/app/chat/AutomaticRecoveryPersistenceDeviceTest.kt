package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.engine.AutomaticRecoverySettlement
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
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
                    AutomaticInputRecovery(storage, clock, {
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
                assertEquals(1, storage.messages.listBySession("session").count { it.kind == "RECOVERY_NOTICE" })
            }
        }

    @Test fun userStopDoesNotReleaseParkedInputs() =
        runBlocking {
            withStorage { storage ->
                storage.sessionInputs.accept(input())
                storage.sessionInputs.parkSessionInputs("session", "PROCESS_INTERRUPTED", 2)
                val turn = storage.turns.start("stopped", "session", 1)
                storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 2, "USER_STOP")
                AutomaticInputRecovery(storage, clock, { error("must not revalidate after Stop") }, { "Ended" })
                    .recover("session")
                assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("input")?.state)
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
                        storage,
                        clock,
                        { _, _ -> error("no runtime") },
                        settlement::finish,
                        { "Could not inspect" },
                    )
                repeat(2) { recovery.recover("parent") }
                assertEquals(1, storage.turns.listBySession("session").size)
                assertEquals(1, storage.messages.listBySession("session").count { it.kind == "RECOVERY_NOTICE" })
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
                    com.helix.app.engine
                        .TurnEngine(storage, clock) { UUID.randomUUID().toString() }
                engine.requestCancel(turn.id, "session", false, false, "USER_STOP")
                val stopped = storage.turns.resolve(turn.id)
                assertEquals("NEEDS_REVIEW", stopped.state)
                assertEquals("USER_STOP", stopped.errorCode)
                assertTrue(
                    !com.helix.app.engine.AutomaticRecoveryPolicy
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
        val storage = HelixStorage.open(context, name, directory)
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
