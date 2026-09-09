package com.helix.tools.automation

import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import java.time.Duration

/** Bounded polling primitive. Callers must run it off the Android main thread. */
class AutomationWaiter(
    private val clock: Clock = SystemClock(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    @Suppress("ReturnCount", "ComplexCondition")
    fun waitFor(
        query: AutomationFindQuery,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(200),
        snapshotProvider: () -> AutomationSnapshotResult,
    ): AutomationWaitResult {
        if (
            timeout.isNegative ||
            timeout.isZero ||
            timeout > MAX_TIMEOUT ||
            pollInterval < MIN_POLL ||
            pollInterval > MAX_POLL
        ) {
            return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
        }
        val deadline = clock.now().plus(timeout)
        while (clock.now().isBefore(deadline)) {
            val result = snapshotProvider()
            if (result.status != AutomationSnapshotStatus.SUCCESS || result.snapshot == null) {
                return AutomationWaitResult(AutomationWaitStatus.SNAPSHOT_REFUSED)
            }
            val found = AutomationFinder.find(result.snapshot, query)
            if (found.status == AutomationFindStatus.INVALID_QUERY) {
                return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
            }
            if (found.status == AutomationFindStatus.FOUND) {
                return AutomationWaitResult(AutomationWaitStatus.FOUND, found.nodes)
            }
            sleeper(pollInterval)
        }
        return AutomationWaitResult(AutomationWaitStatus.TIMED_OUT)
    }

    companion object {
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(10)
        val MIN_POLL: Duration = Duration.ofMillis(50)
        val MAX_POLL: Duration = Duration.ofSeconds(1)
    }
}
