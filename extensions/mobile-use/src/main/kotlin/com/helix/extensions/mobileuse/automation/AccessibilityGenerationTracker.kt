package com.helix.extensions.mobileuse.automation

import java.util.concurrent.atomic.AtomicLong

internal class AccessibilityGenerationTracker {
    private val generation = AtomicLong(1L)

    fun current(): Long = generation.get()

    fun contentChanged(): Long = generation.incrementAndGet()

    fun eventReceived(
        eventWindowId: Int,
        activeWindowId: Int?,
        windowMayHaveChanged: Boolean,
    ): Long = if (windowMayHaveChanged) contentChanged() else contentChangedInWindow(eventWindowId, activeWindowId)

    fun contentChangedInWindow(
        eventWindowId: Int,
        activeWindowId: Int?,
    ): Long {
        val targetWindowId = activeWindowId ?: -1
        return if (eventWindowId >= 0 && targetWindowId >= 0 && eventWindowId != targetWindowId) {
            current()
        } else {
            contentChanged()
        }
    }
}
