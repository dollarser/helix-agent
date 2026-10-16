package com.helix.app.chat

import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryWindowTest {
    private fun turn(
        id: String,
        state: String = "COMPLETED",
    ) = TurnEntity(id, "s", state, 0, 1, null, null)

    private fun row(
        turn: String,
        id: String,
    ) = ToolTimelineRow(turn, id, "read", "{}", "done", null, null)

    @Test fun settledRowsOutsideTheSelectedWindowAreNotReintroduced() {
        val selected = row("old", "selected")
        val later = row("new", "later")
        assertEquals(
            listOf(selected),
            scopedLiveRows(listOf(turn("old"), turn("new")), listOf(selected), listOf(selected, later)),
        )
    }

    @Test fun activeExecutionAndRecoveryControlsSurviveHistoryNavigation() {
        val running = row("active", "running")
        val recovery = row("stopped", "recovery").copy(prootRecoveryBusy = true)
        val rows =
            scopedLiveRows(
                listOf(turn("active", "RUNNING_TOOL"), turn("stopped", "INTERRUPTED")),
                emptyList(),
                listOf(running, recovery),
            )
        assertEquals(listOf(running, recovery), rows)
    }

    @Test fun foreignRowsCannotEnterTheSelectedSessionEvenWithLiveControls() {
        val foreign = row("foreign", "call").copy(prootRecoveryBusy = true)
        assertEquals(emptyList<ToolTimelineRow>(), scopedLiveRows(listOf(turn("owned")), emptyList(), listOf(foreign)))
    }
}
