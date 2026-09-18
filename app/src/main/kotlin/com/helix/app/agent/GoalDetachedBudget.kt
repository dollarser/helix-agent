package com.helix.app.agent

import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Host bridge from one authorized Job to the original Goal clock. No new run, wake or authorization. */
internal class GoalDetachedBudget(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val timerFor: (String) -> GoalTimeBudget?,
    private val idGenerator: () -> String,
    private val monotonicMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    @Suppress("ReturnCount") // Ordinary Turn, missing live Goal clock and accepted lease have distinct outcomes.
    fun prepare(
        sessionId: String,
        turnId: String,
        executionId: String,
        requestedMillis: Long,
    ): Long {
        check(storage.turns.resolve(turnId).sessionId == sessionId)
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return requestedMillis
        val timer = timerFor(turnId) ?: return 0
        val allocation =
            timer.transferToLease(reservationId(executionId), requestedMillis) { lease ->
                check(lease.runId == binding.runId)
                val payload =
                    buildJsonObject {
                        put("version", 1)
                        put("turnId", turnId)
                        put("runId", lease.runId)
                        put("startedElapsedMs", lease.startedElapsedMs)
                        put("durationMs", lease.durationMs)
                    }
                storage.auditEvents.append(
                    eventId(executionId),
                    sessionId,
                    "proot.goal_lease_prepared",
                    "platform",
                    payload.toString(),
                    clock.now().toEpochMilli(),
                )
            }
        return allocation?.durationMs ?: 0
    }

    /** Called only by the original launcher for a proven no-start outcome, in its live process. */
    @Suppress("ReturnCount") // No Goal, no allocation and an already settled allocation need no further accounting.
    fun reject(
        sessionId: String,
        turnId: String,
        executionId: String,
    ) {
        check(storage.turns.resolve(turnId).sessionId == sessionId)
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return
        val reservation = storage.goalUsageReservations.byId(reservationId(executionId)) ?: return
        if (reservation.state != "PENDING") return
        check(reservation.runId == binding.runId && reservation.kind == "TIME_LEASE")
        val event = storage.auditEvents.resolve(eventId(executionId))
        check(event.correlationId == sessionId && event.type == "proot.goal_lease_prepared")
        val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
        check(payload.getValue("version").jsonPrimitive.content == "1")
        check(payload.getValue("turnId").jsonPrimitive.content == turnId)
        check(payload.getValue("runId").jsonPrimitive.content == binding.runId)
        val allocation =
            GoalLeaseAllocation(
                reservation.id,
                binding.runId,
                payload.getValue("startedElapsedMs").jsonPrimitive.long,
                payload.getValue("durationMs").jsonPrimitive.long,
            )
        if (timerFor(turnId)?.completeLease(allocation) != true) {
            // The Turn may have returned/cancelled while the live executor was unwinding.
            val elapsed = monotonicMillis() - allocation.startedElapsedMs
            check(elapsed >= 0) { "no-start accounting cannot cross a clock reset" }
            GoalUsageReservations(
                storage,
            ).checkpointLease(reservation.id, elapsed, clock.now().toEpochMilli(), terminal = true)
        }
        if (TurnState.valueOf(storage.turns.resolve(turnId).state).isTerminal) {
            GoalRunSettlement(storage, clock, idGenerator).settle(turnId)
        }
    }

    private fun reservationId(executionId: String) = "proot-lease-$executionId"

    private fun eventId(executionId: String) = "proot-goal-lease-$executionId"
}
