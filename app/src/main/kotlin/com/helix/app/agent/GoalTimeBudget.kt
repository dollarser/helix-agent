package com.helix.app.agent

import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.recovery.GoalDurableUsageLedger
import com.helix.app.recovery.GoalLeaseClock
import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class GoalTimeLimitException(
    val code: String,
) : RuntimeException(code)

internal data class GoalLeaseAllocation(
    val id: String,
    val runId: String,
    val startedElapsedMs: Long,
    val durationMs: Long,
)

/** Renewable durable time window. Scheduling delays stop admission and are recorded, never truncated. */
@Suppress("TooManyFunctions") // One clock owner: ordinary windows, lease handoff, heartbeat and final settlement.
internal class GoalTimeBudget(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val runId: String,
    private val monotonicMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private data class Window(
        val id: String,
        val started: Long,
        val duration: Long,
        val allocation: GoalLeaseAllocation? = null,
    )

    @Volatile private var window: Window? = null

    @Volatile private var stopCode: String? = null

    @Volatile private var totalDeadline: Long = Long.MAX_VALUE

    @Synchronized
    fun start(): Boolean = renew()

    @Synchronized
    fun expiredCode(): String? {
        val current = window
        return stopCode ?: if (current != null && monotonicMillis() - current.started >= current.duration) {
            if (monotonicMillis() >= totalDeadline) "GOAL_BUDGET_LIMIT" else "GOAL_TIME_WINDOW_EXPIRED"
        } else {
            null
        }
    }

    fun checkActive() {
        expiredCode()?.let { throw GoalTimeLimitException(it) }
    }

    @Synchronized
    fun remainingExecutionMillis(): Long =
        if (expiredCode() != null || window == null) 0 else (totalDeadline - monotonicMillis()).coerceAtLeast(0)

    @Synchronized
    fun pulse(): Boolean {
        if (stopCode != null) return false
        val current = requireNotNull(window)
        val elapsed = (monotonicMillis() - current.started).coerceAtLeast(0)
        var continued = false
        if (elapsed >= current.duration) {
            // Keep the final reservation pending until cancellation has actually finished;
            // finish() then records the full observed time, including cancellation latency.
            stopCode = if (monotonicMillis() >= totalDeadline) "GOAL_BUDGET_LIMIT" else "GOAL_TIME_WINDOW_EXPIRED"
        } else {
            storage.withTransaction {
                if (current.allocation == null) {
                    settle(elapsed)
                    continued = renew(current.started + elapsed)
                } else {
                    GoalUsageReservations(storage).checkpointLease(current.id, elapsed, clock.now().toEpochMilli())
                    val held =
                        GoalLeaseClock(storage).hold(
                            current.id,
                            GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS,
                            GoalUsageReservations.MAX_LEASE_MILLIS,
                        )
                    continued = held > 0
                    if (continued) {
                        window = current.copy(duration = elapsed + held)
                    } else {
                        stopCode = "GOAL_BUDGET_LIMIT"
                    }
                }
            }
        }
        return continued
    }

    /** Atomic handoff. Failure leaves the original TIME reservation and live window intact. */
    @Synchronized
    @Suppress("ReturnCount") // Distinct admission gates leave the original clock unchanged.
    fun transferToLease(
        id: String,
        requestedMillis: Long,
    ): GoalLeaseAllocation? {
        require(id.isNotBlank() && requestedMillis in 1_000..GoalUsageReservations.MAX_LEASE_MILLIS)
        val current = window ?: return null
        if (current.allocation != null || expiredCode() != null) return null
        val now = monotonicMillis()
        val remaining = remainingExecutionMillis()
        val duration = minOf(requestedMillis, remaining)
        if (duration < 1_000) return null
        val clockDuration = maxOf(duration, minOf(remaining, GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS))
        val allocation = GoalLeaseAllocation(id, runId, now, duration)
        try {
            storage.withTransaction {
                settle((now - current.started).coerceAtLeast(0))
                val admitted =
                    GoalUsageReservations(storage).reserve(
                        GoalUsageReservations.Request(
                            id,
                            runId,
                            GoalUsageReservations.Kind.TIME_LEASE,
                            millis = clockDuration,
                        ),
                    )
                if (!admitted) throw LeaseAdmissionRefused()
            }
        } catch (_: LeaseAdmissionRefused) {
            return null
        }
        window = Window(id, now, clockDuration, allocation)
        return allocation
    }

    /** The original Job is proven stopped; an active Turn continues on ordinary heartbeat windows. */
    @Synchronized
    fun completeLease(allocation: GoalLeaseAllocation): Boolean {
        val current = window
        if (current == null || current.allocation != allocation || expiredCode() != null) return false
        val now = monotonicMillis()
        storage.withTransaction {
            GoalUsageReservations(storage).checkpointLease(
                current.id,
                (now - current.started).coerceAtLeast(0),
                clock.now().toEpochMilli(),
                terminal = true,
            )
            renew(now)
        }
        return true
    }

    @Synchronized
    fun finish() {
        window?.let { current ->
            val elapsed = (monotonicMillis() - current.started).coerceAtLeast(0)
            if (current.allocation == null) {
                settle(elapsed)
            } else {
                storage.withTransaction {
                    GoalUsageReservations(storage).checkpointLease(current.id, elapsed, clock.now().toEpochMilli())
                    // Release spare heartbeat capacity; keep only the original Job's remaining allocation.
                    GoalLeaseClock(
                        storage,
                    ).hold(current.id, 0, (current.allocation.durationMs - elapsed).coerceAtLeast(0))
                }
            }
        }
        window = null
    }

    private class LeaseAdmissionRefused : RuntimeException()

    suspend fun <T> run(
        onExpiry: () -> Unit,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            if (!start()) throw GoalTimeLimitException("GOAL_BUDGET_LIMIT")
            val worker = async { block() }
            val monitor =
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val current = requireNotNull(window)
                        delay(minOf(1_000L, current.duration).coerceAtLeast(1))
                        if (!pulse()) {
                            onExpiry()
                            worker.cancel()
                            break
                        }
                    }
                }
            try {
                val result = worker.await()
                checkActive()
                result
            } catch (e: ApprovalCancelledException) {
                val code = expiredCode()
                if (code != null) throw GoalTimeLimitException(code)
                throw e
            } catch (e: CancellationException) {
                val code = expiredCode()
                if (code != null) throw GoalTimeLimitException(code)
                throw e
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    monitor.cancelAndJoin()
                    finish()
                }
            }
        }

    private fun settle(elapsed: Long) {
        val current = requireNotNull(window)
        GoalUsageReservations(storage).settle(current.id, 0, elapsed, clock.now().toEpochMilli())
    }

    private fun renew(started: Long = monotonicMillis()): Boolean {
        var renewed = false
        storage.withTransaction {
            val run = storage.goalRuns.resolve(runId)
            val goal = storage.goals.resolve(run.goalId)
            val held = storage.goalUsageReservations.pendingForRun(runId).sumOf { it.reservedMillis }
            val remaining =
                minOf(
                    goal.budgets.maxDurationMillis - goal.runTimeMillis,
                    goal.budgets.maxWakeDurationMillis - goal.currentWakeMillis,
                ) -
                    held
            val duration = minOf(remaining, GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS)
            if (duration > 0) {
                val id = "goal-time:${UUID.randomUUID()}"
                renewed =
                    GoalUsageReservations(storage).reserve(
                        GoalUsageReservations.Request(id, runId, GoalUsageReservations.Kind.TIME, millis = duration),
                    )
                if (renewed) {
                    window = Window(id, started, duration)
                    if (totalDeadline ==
                        Long.MAX_VALUE
                    ) {
                        totalDeadline = started + minOf(remaining, Long.MAX_VALUE - started)
                    }
                }
            }
            if (!renewed) stopCode = "GOAL_BUDGET_LIMIT"
        }
        return renewed
    }
}
