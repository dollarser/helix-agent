package com.helix.app.terminal

import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TerminalWindowSlotsTest {
    private fun live(id: String) = ManualTerminal.State(id, "RUNNING", ".", null, null, false)

    @Test fun fourSlotsSurviveReorderAndRestartAndRecycleOnlyClosedWindow() {
        val store = InMemoryLineStore()
        val states = (1..4).map { live("shell-$it") }
        val slots = TerminalWindowSlots(store)
        val original = slots.present(states).associate { it.sessionId to it.displayNumber }
        assertEquals(setOf(1L, 2L, 3L, 4L), original.values.toSet())
        val restarted = TerminalWindowSlots(store)
        assertEquals(original, restarted.present(states.reversed()).associate { it.sessionId to it.displayNumber })
        val replacement = restarted.present(states.drop(1) + live("fresh-uuid"))
        assertEquals(1L, replacement.last().displayNumber)
        assertEquals(null, restarted.number("shell-1"))
        assertEquals("fresh-uuid", replacement.last().sessionId)
    }

    @Test fun stoppedAndMissingRuntimeRecordsDoNotBecomeWindowsOrMintStopProof() {
        val store = InMemoryLineStore()
        val lost = live("lost").copy(phase = "UNKNOWN")
        val stopped = live("stopped").copy(phase = "STOPPED", canSettle = true)
        val slots = TerminalWindowSlots(store)
        assertTrue(slots.present(listOf(lost, stopped)).isEmpty())
        assertFalse(lost.canSettle)
        assertEquals(1L, slots.present(listOf(live("new"))).single().displayNumber)
    }

    @Test fun fullRefusalDoesNotChangeStoredWindows() {
        val store = InMemoryLineStore()
        val slots = TerminalWindowSlots(store)
        val four = (1..4).map { live("shell-$it") }
        slots.present(four)
        val before = store.lines("windows")
        assertThrows(IllegalArgumentException::class.java) { slots.present(four + live("fifth")) }
        assertEquals(before, store.lines("windows"))
    }

    @Test fun concurrentWrappersKeepSameUniqueSlots() {
        val store = InMemoryLineStore()
        val states = (1..4).map { live("shell-$it") }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results =
                pool
                    .invokeAll(
                        (1..16).map { Callable { TerminalWindowSlots(store).present(states) } },
                    ).map { it.get() }
            assertEquals(1, results.toSet().size)
            assertEquals(
                4,
                results
                    .first()
                    .map { it.displayNumber }
                    .toSet()
                    .size,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun persistenceFailureDoesNotReportUnstoredWindowOrChangeUnrelatedSettings() {
        val backing = InMemoryLineStore()
        backing.setLines("custom-title", listOf("User title"))
        val failing =
            object : LineStore {
                override fun lines(key: String) = backing.lines(key)

                override fun setLines(
                    key: String,
                    lines: List<String>,
                ) {
                    error("storage unavailable")
                }
            }
        assertThrows(IllegalStateException::class.java) { TerminalWindowSlots(failing).present(listOf(live("a"))) }
        assertTrue(backing.lines("windows").isEmpty())
        assertEquals(listOf("User title"), backing.lines("custom-title"))
    }

    @Test fun corruptSlotsAreNotSilentlyResetToDuplicateNumbers() {
        val store = InMemoryLineStore()
        val corrupt = listOf("1", "a", "1", "b", "1")
        store.setLines("windows", corrupt)
        assertThrows(IllegalArgumentException::class.java) { TerminalWindowSlots(store).present(listOf(live("a"))) }
        assertEquals(corrupt, store.lines("windows"))
    }

    @Test fun startingAndStoppingPhysicalProcessesKeepTheirSlotUntilConfirmedExit() {
        val slots = TerminalWindowSlots(InMemoryLineStore())
        val start = live("a").copy(phase = "STARTING")
        val stop = live("b").copy(phase = "STOPPING")
        assertEquals(listOf(1L, 2L), slots.present(listOf(start, stop)).map { it.displayNumber })
        assertEquals(2L, slots.present(listOf(stop)).single().displayNumber)
        assertEquals(1L, slots.present(listOf(stop, live("c"))).last().displayNumber)
    }
}
