package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** HXA-039 device fixture for current-call identity and transactional model-visible writes. */
@RunWith(AndroidJUnit4::class)
class TurnCoordinatorDeviceTest {
    @Test
    fun repositoryRejectsTurnStateBypass() {
        val storage = isolatedStorage()
        try {
            storage.sessions.create("session-1", "Transitions", null, null, 1_000L)
            val turn = storage.turns.start("turn-1", "session-1", 1_000L)

            assertThrows(IllegalArgumentException::class.java) {
                storage.turns.updateState(turn, TurnState.RUNNING_TOOL, 1, null, null)
            }
            assertEquals(TurnState.CREATED.name, storage.turns.resolve("turn-1").state)
        } finally {
            storage.close()
        }
    }

    @Test
    fun secondModelFailureTerminalizesSecondCallAndKeepsItsPartialText() {
        val storage = isolatedStorage()
        try {
            storage.sessions.create("session-1", "Coordinator", null, null, 1_000L)
            var nextId = 0
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    FixedClock(2_000L),
                    { "generated-${nextId++}" },
                    TurnStartSpec("session-1", "turn-1", "model-1", "snapshot", "hello"),
                )

            val first = coordinator.beginModelStream()
            first.apply(ModelEvent.ToolCallStarted(0, ToolCallId("tool-1"), "time.now"), ::label)
            first.apply(ModelEvent.ToolArgumentsDelta(0, "{}"), ::label)
            first.apply(ModelEvent.ToolCallFinished(0), ::label)
            coordinator.beginToolBatch(listOf("tool-1"))
            coordinator.commitModelToolStep("""[{"id":"tool-1","name":"time.now","arguments":"{}"}]""")
            coordinator.settleBatchCall("tool-1", sideEffectUnknown = false)
            coordinator.openNextModelCall(
                listOf(TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, TOOL_RESULT)),
                "model-2",
            )

            val second = coordinator.beginModelStream()
            second.apply(ModelEvent.TextDelta("partial-second"), ::label)
            coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "INTERNAL", "failed"))

            assertEquals(TurnState.FAILED.name, storage.turns.resolve("turn-1").state)
            assertEquals("COMPLETED", storage.modelCalls.resolve("model-1").state)
            assertEquals("FAILED", storage.modelCalls.resolve("model-2").state)
            val assistantTexts =
                storage.messages
                    .listBySession("session-1")
                    .filter { it.role == ModelRole.ASSISTANT.name && it.kind == ChatHistoryBuilder.KIND_TEXT }
                    .mapNotNull(storage.messages::readContent)
            assertEquals(listOf("partial-second"), assistantTexts)
            assertTrue(
                storage.messages.listBySession("session-1").any { it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT },
            )
        } finally {
            storage.close()
        }
    }

    private fun isolatedStorage(): HelixStorage {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        return HelixStorage.open(context, "turn-coordinator-$suffix.db", File(context.filesDir, "turn-$suffix"))
    }

    private fun label(code: com.helix.core.model.ModelErrorCode): String = code.name

    private companion object {
        const val TOOL_RESULT = """{"id":"tool-1","tool":"time.now","status":"SUCCEEDED","summary":"ok"}"""
    }
}

private class FixedClock(
    private val millis: Long,
) : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(millis)
}
