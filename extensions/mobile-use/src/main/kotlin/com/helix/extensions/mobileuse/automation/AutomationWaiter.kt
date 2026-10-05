package com.helix.extensions.mobileuse.automation

import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import java.time.Duration

/** One bounded observation primitive; all orchestration and reactions stay with the model. */
class AutomationWaiter(
    private val clock: Clock = SystemClock(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun waitFor(
        query: AutomationFindQuery,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(200),
        condition: AutomationWaitCondition = AutomationWaitCondition.PRESENT,
        stableFor: Duration = Duration.ofMillis(500),
        cancelled: () -> Boolean = { false },
        snapshotProvider: () -> AutomationSnapshotResult,
    ): AutomationWaitResult {
        val validTimeout = !timeout.isNegative && !timeout.isZero && timeout <= MAX_TIMEOUT
        val validPoll = pollInterval in MIN_POLL..MAX_POLL
        val validStability = stableFor in MIN_POLL..MAX_TIMEOUT
        if (!validTimeout || !validPoll || !validStability) {
            return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
        }
        val deadline = clock.now().plus(timeout)
        if (query.maxResults !in 1..50) return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
        var baseline: String? = null
        var previous: String? = null
        var stableSince = clock.now()
        var latest: AutomationSnapshotResult? = null
        while (clock.now().isBefore(deadline)) {
            if (cancelled()) return AutomationWaitResult(AutomationWaitStatus.CANCELLED, observation = latest)
            latest = snapshotProvider()
            if (cancelled()) return AutomationWaitResult(AutomationWaitStatus.CANCELLED, observation = latest)
            if (!clock.now().isBefore(deadline)) {
                return AutomationWaitResult(AutomationWaitStatus.TIMED_OUT, observation = latest)
            }
            val snapshot = latest.snapshot
            if (latest.status == AutomationSnapshotStatus.UNSUPPORTED_UI) {
                previous = null
                stableSince = clock.now()
            } else if (latest.status != AutomationSnapshotStatus.SUCCESS || snapshot == null ||
                latest.pauseReason != null
            ) {
                return AutomationWaitResult(AutomationWaitStatus.SNAPSHOT_REFUSED, observation = latest)
            } else {
                val found = AutomationFinder.findAll(snapshot, query)
                val needsQuery = condition in setOf(AutomationWaitCondition.PRESENT, AutomationWaitCondition.ABSENT)
                val emptyQuery =
                    listOf(query.text, query.contentDescription, query.viewId, query.className).all { it == null } &&
                        query.clickable == null &&
                        query.checkable == null &&
                        query.checked == null
                if (found == null && (needsQuery || !emptyQuery)) {
                    return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT, observation = latest)
                }
                val nodes = found ?: snapshot.nodes
                val fingerprint =
                    nodeFingerprint(
                        listOf(snapshot.packageName, snapshot.windowId.toString()) +
                            nodes.map {
                                it.copy(token = "", parentToken = null).toString()
                            },
                    )
                if (baseline == null) baseline = fingerprint
                if (previous != fingerprint) stableSince = clock.now()
                previous = fingerprint
                val terminal =
                    conditionResult(
                        condition,
                        nodes.isNotEmpty(),
                        snapshot.truncated,
                        fingerprint != baseline,
                        Duration.between(stableSince, clock.now()) >= stableFor,
                    )
                if (terminal != null) return AutomationWaitResult(terminal, nodes.take(query.maxResults), latest)
            }
            val remaining = Duration.between(clock.now(), deadline)
            if (remaining.isNegative || remaining.isZero) break
            try {
                sleeper(minOf(pollInterval, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return AutomationWaitResult(AutomationWaitStatus.CANCELLED, observation = latest)
            }
        }
        return AutomationWaitResult(AutomationWaitStatus.TIMED_OUT, observation = latest)
    }

    private fun conditionResult(
        condition: AutomationWaitCondition,
        hasMatch: Boolean,
        truncated: Boolean,
        changed: Boolean,
        stable: Boolean,
    ): AutomationWaitStatus? =
        when (condition) {
            AutomationWaitCondition.PRESENT -> {
                AutomationWaitStatus.FOUND.takeIf { hasMatch }
            }

            AutomationWaitCondition.ABSENT -> {
                when {
                    hasMatch -> null
                    truncated -> AutomationWaitStatus.INCOMPLETE_SNAPSHOT
                    else -> AutomationWaitStatus.ABSENT
                }
            }

            AutomationWaitCondition.CHANGED -> {
                when {
                    truncated -> AutomationWaitStatus.INCOMPLETE_SNAPSHOT
                    changed -> AutomationWaitStatus.CHANGED
                    else -> null
                }
            }

            AutomationWaitCondition.STABLE -> {
                when {
                    truncated -> AutomationWaitStatus.INCOMPLETE_SNAPSHOT
                    stable -> AutomationWaitStatus.STABLE
                    else -> null
                }
            }
        }

    companion object {
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(60)
        val MIN_POLL: Duration = Duration.ofMillis(50)
        val MAX_POLL: Duration = Duration.ofSeconds(1)
    }
}
