package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLedgerTest {
    private fun item(
        id: String,
        title: String,
        state: TaskItemState,
    ): TaskItem = TaskItem(id, title, state)

    @Test
    fun duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskLedger(listOf(item("a", "t", TaskItemState.TODO), item("a", "t2", TaskItemState.DONE)))
        }
    }

    @Test
    fun overCapacityIsRejected() {
        val items = (1..TaskLedger.MAX_ITEMS + 1).map { item("t$it", "title", TaskItemState.TODO) }
        assertThrows(IllegalArgumentException::class.java) { TaskLedger(items) }
    }

    @Test
    fun blankTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TaskItem("a", "  ", TaskItemState.TODO) }
    }

    @Test
    fun emptyLedgerIsNotComplete() {
        assertFalse(TaskLedger.EMPTY.isComplete)
        assertEquals(0, TaskLedger.EMPTY.openCount)
        assertNull(TaskLedger.EMPTY.byId("nope"))
    }

    @Test
    fun allDoneIsComplete() {
        val ledger = TaskLedger(listOf(item("a", "t", TaskItemState.DONE), item("b", "t", TaskItemState.DONE)))
        assertTrue(ledger.isComplete)
        assertEquals(2, ledger.doneCount)
        assertEquals(0, ledger.openCount)
    }

    @Test
    fun aBlockedLedgerIsFlaggedAndNotComplete() {
        val ledger = TaskLedger(listOf(item("a", "t", TaskItemState.BLOCKED)))
        assertTrue(ledger.isBlocked)
        assertFalse(ledger.isComplete)
        assertEquals(1, ledger.openCount)
    }

    @Test
    fun withStateReplacesOnlyTheNamedItem() {
        val ledger = TaskLedger(listOf(item("a", "t", TaskItemState.TODO), item("b", "t", TaskItemState.TODO)))
        val updated = ledger.withState("a", TaskItemState.IN_PROGRESS)
        assertEquals(TaskItemState.IN_PROGRESS, updated.byId("a")!!.state)
        assertEquals(TaskItemState.TODO, updated.byId("b")!!.state)
        assertEquals(TaskItemState.TODO, ledger.byId("a")!!.state)
        assertEquals(1, updated.inProgressCount)
    }

    @Test
    fun withStateUnknownIdFails() {
        val ledger = TaskLedger(listOf(item("a", "t", TaskItemState.TODO)))
        assertThrows(IllegalArgumentException::class.java) { ledger.withState("nope", TaskItemState.DONE) }
    }
}
