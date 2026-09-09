package com.helix.app.goal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InterruptedIOException

/** Connects UI coroutine cancellation to bounded blocking evidence reads on their IO worker. */
internal suspend fun <T> readGoalEvidence(block: () -> T): T =
    runInterruptible(Dispatchers.IO) {
        checkGoalEvidenceReadActive()
        block().also { checkGoalEvidenceReadActive() }
    }

internal fun checkGoalEvidenceReadActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("evidence read interrupted")
}
