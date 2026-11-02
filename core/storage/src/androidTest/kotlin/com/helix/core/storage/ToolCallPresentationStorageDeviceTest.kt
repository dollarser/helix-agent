package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolCallPresentationStorageDeviceTest {
    @Test fun settledCallbackHistoryStaysInWindowWhileUnsettledControlsRemainReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tool-window-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            storage.sessions.create("session", "Synthetic callback window", null, null, 1)
            windowCall(storage, "old-settled", ToolCallState.COMPLETED)
            windowCall(storage, "old-review", ToolCallState.NEEDS_REVIEW)
            windowCall(storage, "old-running", ToolCallState.RUNNING)
            val latest = windowCall(storage, "current", ToolCallState.COMPLETED)
            windowCall(storage, "direct", ToolCallState.COMPLETED, hasMessage = false)
            val current = storage.toolCalls.recentBySession("session", latest, Long.MAX_VALUE, 100)
            assertEquals(setOf("old-review", "old-running", "current", "direct"), current.map { it.id }.toSet())
            val oldest = storage.toolCalls.recentBySession("session", 0, 0, 100)
            assertEquals(listOf("old-settled"), oldest.map { it.id })
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun windowCall(
        storage: HelixStorage,
        id: String,
        state: ToolCallState,
        hasMessage: Boolean = true,
    ): Long {
        var turn = storage.turns.start("turn-$id", "session", 2)
        for (next in listOf(TurnState.BUILDING_CONTEXT, TurnState.WAITING_MODEL, TurnState.RECEIVING_MODEL)) {
            turn = storage.turns.updateState(turn, next, 0, null, null)
        }
        if (state == ToolCallState.RUNNING) {
            turn = storage.turns.updateState(turn, TurnState.RUNNING_TOOL, 0, null, null)
        } else {
            storage.turns.updateState(turn, TurnState.COMPLETED, 0, 3, null)
        }
        val message =
            if (hasMessage) {
                storage.messages.append(
                    "message-$id",
                    "session",
                    turn.id,
                    "USER",
                    "TEXT",
                    "Synthetic $id",
                )
            } else {
                null
            }
        storage.toolCalls.append(id, turn.id, id, "time.now", "1", "{}", state.name)
        if (state == ToolCallState.COMPLETED) {
            val result = storage.toolResults.append("result-$id", id, "SUCCEEDED", "Synthetic settled result", null)
            storage.toolResults.markVerified(result)
        }
        return message?.sequence ?: -1
    }

    @Test
    fun modelIntentSurvivesStorageReopenWithBusinessArgsUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tool-presentation-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val states = listOf("COMPLETED", "AWAITING_APPROVAL", "DENIED", "FAILED", "NEEDS_REVIEW")
        try {
            val storage = HelixStorage.open(context, name, root)
            try {
                storage.sessions.create("session", "Tool intent", null, null, 1)
                storage.turns.start("turn", "session", 2)
                states.forEach { state ->
                    storage.toolCalls.append(
                        id = state,
                        turnId = "turn",
                        callId = state,
                        name = "time.now",
                        version = "1",
                        argsJson = "{}",
                        state = state,
                        modelIntent = "Inspect $state",
                    )
                }
            } finally {
                storage.close()
            }

            val reopened = HelixStorage.open(context, name, root)
            try {
                val calls = states.map(reopened.toolCalls::resolve)
                calls.zip(states).forEach { (call, state) ->
                    assertEquals("{}", call.argsJson)
                    assertEquals("Inspect $state", call.modelIntent)
                    assertEquals(state, call.state)
                }
                // Different presentation/state cannot change the business-argument identity.
                assertEquals(1, calls.map { it.argsHash }.distinct().size)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
