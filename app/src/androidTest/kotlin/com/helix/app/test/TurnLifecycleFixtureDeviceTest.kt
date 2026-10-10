package com.helix.app.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class TurnLifecycleFixtureDeviceTest {
    @Test fun originalMutableClockAndIdClosureReachRealEngineSettlement() =
        fixture { storage ->
            var millis = 1_000L
            var nextId = 0
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(millis)
                }
            val turn = startFixtureTurn(storage, clock, { "own-${nextId++}" }, spec())
            turn.beginModelStream().apply(ModelEvent.TextDelta("owned response"))
            val expectedId = "own-$nextId"
            millis = 2_000L
            turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals(2_000L, storage.turns.resolve("turn").endedAt)
            val assistant = storage.messages.listBySession("session").single { it.role == "ASSISTANT" }
            assertEquals(expectedId, assistant.id)
            assertEquals("owned response", storage.messages.readContent(assistant))
        }

    @Test fun unregisteredContextFailsWithoutGuessingGlobalStoreOrClock() =
        fixture { storage ->
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(1_000)
                }
            val turn = TurnCoordinator.start(storage, clock, { UUID.randomUUID().toString() }, spec())
            turn.beginModelStream().apply(ModelEvent.TextDelta("uncommitted"))
            val before = storage.messages.listBySession("session")
            assertThrows(IllegalStateException::class.java) {
                turn.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
            assertEquals(before, storage.messages.listBySession("session"))
            assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve("turn").state)
        }

    private fun spec() = TurnStartSpec("session", "turn", "model", "snapshot", "initial")

    private fun fixture(action: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lifecycle-${UUID.randomUUID()}"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create("session", "Own fixture", null, null, 1_000)
            action(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
