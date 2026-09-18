package com.helix.app.recovery

import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.GoalUsageReservationEntity
import com.helix.core.storage.mapping.StoredGoal

/** Admission journal. A true result authorizes only the first local execution, never a retry. */
class GoalUsageReservations(
    private val storage: HelixStorage,
) {
    enum class Kind { MODEL, TOOL, TIME, TIME_LEASE }

    data class Request(
        val id: String,
        val runId: String,
        val kind: Kind,
        val tokens: Long = 0,
        val millis: Long = 0,
    )

    fun reserve(request: Request): Boolean {
        validate(request)
        var admitted = false
        storage.withTransaction {
            if (storage.goalUsageReservations.byId(request.id) == null) {
                val run = storage.goalRuns.resolve(request.runId)
                val goal = storage.goals.resolve(run.goalId)
                val pending = storage.goalUsageReservations.pendingForRun(run.id)
                if (run.endedAt == null && goal.state == GoalState.RUNNING.name && fits(goal, pending, request)) {
                    storage.goalUsageReservations.insert(
                        GoalUsageReservationEntity(
                            request.id,
                            run.id,
                            request.kind.name,
                            request.tokens,
                            request.millis,
                            "PENDING",
                            null,
                            null,
                        ),
                    )
                    admitted = true
                }
            }
        }
        return admitted
    }

    /** Settlement is atomic with usage/audit, and a repeated or late completion cannot charge twice. */
    fun settle(
        id: String,
        tokens: Long,
        millis: Long,
        atMillis: Long,
    ): Boolean {
        var changed = false
        storage.withTransaction {
            val reservation = requireNotNull(storage.goalUsageReservations.byId(id)) { "reservation missing" }
            if (reservation.state == "PENDING") {
                apply(reservation, tokens, millis, atMillis, interrupted = false)
                changed = true
            }
        }
        return changed
    }

    /**
     * One cumulative clock covers both the active Turn and its detached Job. Repeated/older
     * observations never charge twice; terminal proof releases only the unspent capacity.
     * Callers must compute the union of execution intervals, not sum overlapping timers.
     */
    fun checkpointLease(
        id: String,
        observedMillis: Long,
        atMillis: Long,
        terminal: Boolean = false,
    ): Boolean {
        require(observedMillis >= 0)
        var changed = false
        storage.withTransaction {
            val reservation = requireNotNull(storage.goalUsageReservations.byId(id))
            require(reservation.kind == Kind.TIME_LEASE.name)
            if (reservation.state == "PENDING" &&
                (terminal || observedMillis > (reservation.chargedMillis ?: 0L))
            ) {
                applyLease(reservation, observedMillis, atMillis, interrupted = false, terminal)
                changed = true
            }
        }
        return changed
    }

    /** Unknown in-flight work consumes its reservation once. No offline wall time or execution replay. */
    fun recoverRun(
        runId: String,
        atMillis: Long,
        includeLeases: Boolean = true,
    ) {
        storage.withTransaction {
            storage.goalUsageReservations.pendingForRun(runId).forEach {
                if (it.kind != Kind.TIME_LEASE.name || includeLeases) {
                    val millis =
                        if (it.kind == Kind.TIME_LEASE.name) {
                            Math.addExact(it.reservedMillis, it.chargedMillis ?: 0L)
                        } else {
                            it.reservedMillis
                        }
                    apply(it, it.reservedTokens, millis, atMillis, interrupted = true)
                }
            }
        }
    }

    private fun apply(
        reservation: GoalUsageReservationEntity,
        tokens: Long,
        millis: Long,
        atMillis: Long,
        interrupted: Boolean,
    ) {
        val kind = Kind.valueOf(reservation.kind)
        require(tokens >= 0 && millis >= 0)
        require(kind == Kind.MODEL || tokens == 0L)
        if (kind == Kind.TIME_LEASE) {
            applyLease(reservation, millis, atMillis, interrupted, terminal = true)
            return
        }
        require(kind == Kind.TIME || millis == 0L)
        val run = storage.goalRuns.resolve(reservation.runId)
        var trailingMillis = millis
        // A delayed live checkpoint records all observed elapsed time. Keep its reservation
        // pending until the final chunk so budget closure cannot discard the overrun.
        while (trailingMillis > GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS) {
            GoalDurableUsageLedger(storage).checkpoint(
                run.goalId,
                run.id,
                GoalDurableUsageLedger.Boundary.HEARTBEAT,
                GoalDurableUsageLedger.Delta(durationMillis = GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS),
                atMillis,
            )
            trailingMillis -= GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS
        }
        storage.goalUsageReservations.settle(reservation.id, interrupted, tokens, millis)
        val boundary =
            when (kind) {
                Kind.MODEL -> GoalDurableUsageLedger.Boundary.MODEL
                Kind.TOOL -> GoalDurableUsageLedger.Boundary.TOOL
                Kind.TIME, Kind.TIME_LEASE -> GoalDurableUsageLedger.Boundary.HEARTBEAT
            }
        GoalDurableUsageLedger(storage).checkpoint(
            run.goalId,
            run.id,
            boundary,
            GoalDurableUsageLedger.Delta(
                modelCalls = if (kind == Kind.MODEL) 1 else 0,
                toolCalls = if (kind == Kind.TOOL) 1 else 0,
                tokens = tokens,
                durationMillis = trailingMillis,
            ),
            atMillis,
        )
    }

    private fun applyLease(
        reservation: GoalUsageReservationEntity,
        observedMillis: Long,
        atMillis: Long,
        interrupted: Boolean,
        terminal: Boolean,
    ) {
        val charged = reservation.chargedMillis ?: 0L
        val total = maxOf(charged, observedMillis)
        var delta = total - charged
        val remaining = (reservation.reservedMillis - delta).coerceAtLeast(0L)
        val run = storage.goalRuns.resolve(reservation.runId)
        val ledger = GoalDurableUsageLedger(storage)
        // Keep the owner pending through all chunks, including observed cancellation overrun.
        while (delta > GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS) {
            ledger.checkpoint(
                run.goalId,
                run.id,
                GoalDurableUsageLedger.Boundary.HEARTBEAT,
                GoalDurableUsageLedger.Delta(durationMillis = GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS),
                atMillis,
            )
            delta -= GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS
        }
        storage.goalUsageReservations.checkpointLease(reservation.id, remaining, total)
        if (terminal) storage.goalUsageReservations.settle(reservation.id, interrupted, 0, total)
        ledger.checkpoint(
            run.goalId,
            run.id,
            GoalDurableUsageLedger.Boundary.HEARTBEAT,
            GoalDurableUsageLedger.Delta(durationMillis = delta),
            atMillis,
        )
    }

    private fun ownsClock(
        pending: List<GoalUsageReservationEntity>,
        request: Request,
    ): Boolean =
        when (request.kind) {
            Kind.TIME_LEASE -> pending.none { it.kind in setOf(Kind.TIME.name, Kind.TIME_LEASE.name) }
            Kind.TIME -> pending.none { it.kind == Kind.TIME_LEASE.name }
            else -> true
        }

    private fun fits(
        goal: StoredGoal,
        pending: List<GoalUsageReservationEntity>,
        request: Request,
    ): Boolean {
        val models = pending.count { it.kind == Kind.MODEL.name }.toLong() + if (request.kind == Kind.MODEL) 1 else 0
        val tools = pending.count { it.kind == Kind.TOOL.name }.toLong() + if (request.kind == Kind.TOOL) 1 else 0
        val tokens = pending.sumOf { it.reservedTokens }
        val millis = pending.sumOf { it.reservedMillis }
        return ownsClock(pending, request) && goal.modelCalls < goal.budgets.maxModelCalls &&
            goal.toolCalls < goal.budgets.maxToolCalls &&
            goal.totalTokens < goal.budgets.maxTotalTokens &&
            goal.currentWakeMillis < goal.budgets.maxWakeDurationMillis &&
            goal.runTimeMillis < goal.budgets.maxDurationMillis &&
            models <= goal.budgets.maxModelCalls.toLong() - goal.modelCalls &&
            tools <= goal.budgets.maxToolCalls.toLong() - goal.toolCalls &&
            request.tokens <= goal.budgets.maxTotalTokens - goal.totalTokens - tokens &&
            request.millis <= goal.budgets.maxWakeDurationMillis - goal.currentWakeMillis - millis &&
            request.millis <= goal.budgets.maxDurationMillis - goal.runTimeMillis - millis
    }

    private fun validate(request: Request) {
        require(request.id.isNotBlank() && request.runId.isNotBlank())
        val maximum =
            if (request.kind == Kind.TIME_LEASE) MAX_LEASE_MILLIS else GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS
        require(request.tokens >= 0 && request.millis in 0..maximum)
        require((request.kind == Kind.MODEL) == (request.tokens > 0))
        require((request.kind in setOf(Kind.TIME, Kind.TIME_LEASE)) == (request.millis > 0))
    }

    companion object {
        // ADR-RUNTIME-002 maximum non-renewable background allocation; not the heartbeat crash window.
        const val MAX_LEASE_MILLIS: Long = 30 * 60 * 1_000L
    }
}
