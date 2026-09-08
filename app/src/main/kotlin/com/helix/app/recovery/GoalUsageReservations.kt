package com.helix.app.recovery

import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.GoalUsageReservationEntity
import com.helix.core.storage.mapping.StoredGoal

/** Admission journal. A true result authorizes only the first local execution, never a retry. */
class GoalUsageReservations(
    private val storage: HelixStorage,
) {
    enum class Kind { MODEL, TOOL, TIME }

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

    /** Unknown in-flight work consumes its reservation once. No offline wall time or execution replay. */
    fun recoverRun(
        runId: String,
        atMillis: Long,
    ) {
        storage.withTransaction {
            storage.goalUsageReservations.pendingForRun(runId).forEach {
                apply(it, it.reservedTokens, it.reservedMillis, atMillis, interrupted = true)
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
                Kind.TIME -> GoalDurableUsageLedger.Boundary.HEARTBEAT
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

    private fun fits(
        goal: StoredGoal,
        pending: List<GoalUsageReservationEntity>,
        request: Request,
    ): Boolean {
        val models = pending.count { it.kind == Kind.MODEL.name }.toLong() + if (request.kind == Kind.MODEL) 1 else 0
        val tools = pending.count { it.kind == Kind.TOOL.name }.toLong() + if (request.kind == Kind.TOOL) 1 else 0
        val tokens = pending.sumOf { it.reservedTokens }
        val millis = pending.sumOf { it.reservedMillis }
        return goal.modelCalls < goal.budgets.maxModelCalls &&
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
        require(request.tokens >= 0 && request.millis in 0..GoalDurableUsageLedger.MAX_UNACCOUNTED_MILLIS)
        require((request.kind == Kind.MODEL) == (request.tokens > 0))
        require((request.kind == Kind.TIME) == (request.millis > 0))
    }
}
