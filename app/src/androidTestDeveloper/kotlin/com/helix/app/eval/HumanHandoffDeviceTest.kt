package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.chat.HumanHandoff
import com.helix.app.chat.loadStopFacts
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.feature.conversation.presentation.HumanHandoffState
import com.helix.feature.conversation.presentation.StopEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HumanHandoffDeviceTest {
    @Test fun durableHandoffRejectsOldTaskAndConsumedButtonsAndClassifiesEffectsConservatively() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val storage = c.storage
        val session = "human-${UUID.randomUUID()}"
        val turn = "$session-original"
        val now = System.currentTimeMillis()
        storage.sessions.create(session, "Synthetic human handoff", null, null, now)
        storage.turns.start(turn, session, now)
        try {
            val handoff = HumanHandoff(storage) { System.currentTimeMillis() }
            handoff.record(turn, HumanHandoffState.REQUESTED)
            assertFalse(handoff.canReturn(session, turn))
            val row = storage.turns.resolve(turn)
            val stopping = storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
            storage.turns.updateState(stopping, TurnState.CANCELLED, row.stepCount, System.currentTimeMillis(), null)
            assertTrue(HumanHandoff(storage) { now + 2 }.canReturn(session, turn))
            assertFalse(handoff.canReturn("another-session", turn))
            val states =
                listOf(
                    ToolCallState.COMPLETED,
                    ToolCallState.NEEDS_REVIEW,
                    ToolCallState.CANCELLED,
                    ToolCallState.DENIED,
                )
            for ((index, state) in states.withIndex()) {
                val id = "$turn-call-$index"
                storage.toolCalls.append(id, turn, id, "fixture.action", "1", "{}", state.name)
                if (state == ToolCallState.COMPLETED) {
                    val result =
                        storage.toolResults.append(
                            "$id-result",
                            id,
                            "SUCCEEDED",
                            "Synthetic verified effect",
                            "{}",
                        )
                    storage.toolResults.markVerified(result)
                }
            }
            val effects = loadStopFacts(storage, turn).actions.map { it.effect }
            assertEquals(
                listOf(StopEffect.VERIFIED, StopEffect.UNKNOWN, StopEffect.UNKNOWN, StopEffect.NOT_STARTED),
                effects,
            )
            handoff.record(turn, HumanHandoffState.SUBMITTED)
            assertFalse(HumanHandoff(storage) { now + 3 }.canReturn(session, turn))
            handoff.record(turn, HumanHandoffState.REQUESTED)
            storage.turns.start("$session-new", session, now + 4)
            assertFalse(handoff.canReturn(session, turn))
            assertEquals(0, storage.modelCalls.listByTurn(turn).size)
        } finally {
            storage.deleteSessionPermanently(session)
        }
    }
}
