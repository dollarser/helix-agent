package com.helix.app.agent

import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.recovery.GoalDurableUsageLedger
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

/** Renewable durable time window. Scheduling delays stop admission and are recorded, never truncated. */
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
    )

    @Volatile private var window: Window? = null

    @Volatile private var stopCode: String? = null

    @Volatile private var totalDeadline: Long = Long.MAX_VALUE

    fun start(): Boolean = renew()

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

    fun remainingExecutionMillis(): Long =
        if (expiredCode() != null || window == null) 0 else (totalDeadline - monotonicMillis()).coerceAtLeast(0)

    fun pulse(): Boolean {
        val current = requireNotNull(window)
        val elapsed = (monotonicMillis() - current.started).coerceAtLeast(0)
        var continued = false
        if (elapsed >= current.duration) {
            // Keep the final reservation pending until cancellation has actually finished;
            // finish() then records the full observed time, including cancellation latency.
            stopCode = if (monotonicMillis() >= totalDeadline) "GOAL_BUDGET_LIMIT" else "GOAL_TIME_WINDOW_EXPIRED"
        } else {
            storage.withTransaction {
                settle(elapsed)
                continued = renew(current.started + elapsed)
            }
        }
        return continued
    }

    fun finish() {
        window?.let { settle((monotonicMillis() - it.started).coerceAtLeast(0)) }
        window = null
    }

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
