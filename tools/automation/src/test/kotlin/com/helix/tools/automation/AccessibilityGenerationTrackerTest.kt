package com.helix.tools.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGenerationTrackerTest {
    @Test
    fun unrelatedWindowContentKeepsTheActiveSnapshotGeneration() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        repeat(20) { tracker.contentChangedInWindow(eventWindowId = 767, activeWindowId = 772) }
        assertEquals(initial, tracker.current())
    }

    @Test
    fun activeWindowContentInvalidatesItsSnapshot() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        tracker.contentChangedInWindow(eventWindowId = 772, activeWindowId = 772)
        assertEquals(initial + 1, tracker.current())
    }

    @Test
    fun unknownWindowAndOtherEventInvalidationRemainConservative() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        tracker.contentChangedInWindow(eventWindowId = -1, activeWindowId = 772)
        tracker.contentChangedInWindow(eventWindowId = 767, activeWindowId = null)
        tracker.contentChangedInWindow(eventWindowId = 767, activeWindowId = -1)
        tracker.contentChanged()
        assertEquals(initial + 4, tracker.current())
    }
}
