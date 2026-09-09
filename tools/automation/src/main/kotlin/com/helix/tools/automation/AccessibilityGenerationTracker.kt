package com.helix.tools.automation

import java.util.concurrent.atomic.AtomicLong

internal class AccessibilityGenerationTracker {
    private val generation = AtomicLong(1L)

    fun current(): Long = generation.get()

    fun contentChanged(): Long = generation.incrementAndGet()

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
