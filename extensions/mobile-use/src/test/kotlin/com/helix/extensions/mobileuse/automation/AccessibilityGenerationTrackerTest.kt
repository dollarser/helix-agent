package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGenerationTrackerTest {
    @Test fun ordinaryEventsFromAnotherKnownWindowDoNotInvalidateTheTarget() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        repeat(30) { tracker.eventReceived(88, 44, windowMayHaveChanged = false) }
        assertEquals(initial, tracker.current())
    }

    @Test fun windowReplacementEventsStillInvalidateEvenWhenTheirWindowIsDifferent() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        tracker.eventReceived(88, 44, windowMayHaveChanged = true)
        assertEquals(initial + 1, tracker.current())
    }

    @Test fun targetAndUnidentifiedOrdinaryEventsRemainConservative() {
        val tracker = AccessibilityGenerationTracker()
        val initial = tracker.current()
        tracker.eventReceived(44, 44, windowMayHaveChanged = false)
        tracker.eventReceived(-1, 44, windowMayHaveChanged = false)
        tracker.eventReceived(88, null, windowMayHaveChanged = false)
        assertEquals(initial + 3, tracker.current())
    }

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
