package com.helix.app.infrastructure.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.RequestBudgetDiagnostics
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.Clock
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Exercises the production diagnostic producer and Room sink before model streaming. */
class TurnDiagnosticStoreDeviceTest {
    @Test fun emptyToolCostDoesNotPreventModelStreaming() = verifyToolCost(emptyList())

    @Test fun manyToolCostsRemainBoundedAndDoNotPersistToolContent() =
        verifyToolCost(
            (0 until 100).map { index ->
                ModelToolSchema(
                    ToolName("private.tool_$index"),
                    "private description",
                    "{\"description\":\"${"x".repeat(index * 10)}\"}",
                )
            },
        )

    @Test fun unknownDiagnosticsAndOversizedCostsAreRejectedWithoutJournalChanges() =
        fixture { storage, turn ->
            val before = storage.auditEvents.listByCorrelation("model")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { turn.recordDiagnostic("tools.unknown", "{}") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { turn.recordDiagnostic("tools.cost", "x".repeat(513)) }
            }
            assertEquals(before, storage.auditEvents.listByCorrelation("model"))
            assertEquals(TurnState.WAITING_MODEL.name, storage.turns.resolve("turn").state)
            turn.beginModelStream()
            assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve("turn").state)
        }

    private fun verifyToolCost(tools: List<ModelToolSchema>) =
        fixture { storage, turn ->
            val request = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "private question")), tools)
            val payload = RequestBudgetDiagnostics.tools(request)
            assertTrue(payload.length <= 512)
            turn.recordDiagnostic("tools.cost", payload)
            val record = storage.auditEvents.listByCorrelation("model").single { it.type == "tools.cost" }
            assertEquals(payload, record.redactedPayload)
            assertEquals("agent", record.actor)
            assertFalse(record.redactedPayload.contains("private"))
            assertEquals(TurnState.WAITING_MODEL.name, storage.turns.resolve("turn").state)
            turn.beginModelStream()
            assertEquals(TurnState.RECEIVING_MODEL.name, storage.turns.resolve("turn").state)
            assertEquals("RUNNING", storage.modelCalls.resolve("model").state)
        }

    private fun fixture(block: suspend (HelixStorage, TurnRoundCoordinator) -> Unit) =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "turn-diagnostics-${UUID.randomUUID()}.db"
            val files = File(context.cacheDir, name)
            val storage = HelixStorage.open(context, name, files)
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(2_000)
                }
            try {
                storage.sessions.create("session", "Diagnostics fixture", null, null, 1_000)
                val turn =
                    RoomTurnRoundStore.start(
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
