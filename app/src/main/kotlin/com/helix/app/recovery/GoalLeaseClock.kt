package com.helix.app.recovery

import com.helix.core.storage.HelixStorage

/** Adjusts only the Goal clock's unspent capacity. It never changes a Runtime lease or starts work. */
internal class GoalLeaseClock(
    private val storage: HelixStorage,
) {
    fun hold(
        id: String,
        minimumMillis: Long,
        maximumMillis: Long,
    ): Long {
        require(minimumMillis in 0..maximumMillis && maximumMillis <= GoalUsageReservations.MAX_LEASE_MILLIS)
        var held = 0L
        storage.withTransaction {
            val reservation = requireNotNull(storage.goalUsageReservations.byId(id))
            require(reservation.kind == GoalUsageReservations.Kind.TIME_LEASE.name)
            if (reservation.state != "PENDING") return@withTransaction
            val run = storage.goalRuns.resolve(reservation.runId)
            val goal = storage.goals.resolve(run.goalId)
            if (run.endedAt != null || goal.state != "RUNNING") return@withTransaction
            held =
                maxOf(reservation.reservedMillis, minimumMillis)
                    .coerceAtMost(maximumMillis)
            storage.goalUsageReservations.checkpointLease(id, held, reservation.chargedMillis ?: 0L)
        }
        return held
    }
}
