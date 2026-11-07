package com.helix.core.storage.repository

import com.helix.core.storage.dao.GoalUsageReservationDao
import com.helix.core.storage.entity.GoalUsageReservationEntity

class GoalUsageReservationRepository(
    private val dao: GoalUsageReservationDao,
) {
    fun byId(id: String): GoalUsageReservationEntity? = dao.byId(id)

    fun pendingForRun(runId: String): List<GoalUsageReservationEntity> = dao.pendingForRun(runId)

    fun insert(value: GoalUsageReservationEntity) {
        require(value.id.isNotBlank() && value.runId.isNotBlank())
        require(value.kind in setOf("MODEL", "TOOL", "TIME", "TIME_LEASE"))
        require(value.reservedTokens >= 0 && value.reservedMillis >= 0)
        require(value.state == "PENDING" && value.chargedTokens == null && value.chargedMillis == null)
        dao.insert(value)
    }

    /** Pending lease rows hold only unconsumed capacity; chargedMillis is cumulative elapsed usage. */
    fun checkpointLease(
        id: String,
        remainingMillis: Long,
        chargedMillis: Long,
    ) {
        require(remainingMillis >= 0 && chargedMillis >= 0)
        check(dao.checkpointLease(id, remainingMillis, chargedMillis) == 1) { "time lease is not pending" }
    }

    fun settle(
        id: String,
        interrupted: Boolean,
        tokens: Long,
        millis: Long,
    ) {
        require(tokens >= 0 && millis >= 0)
        check(dao.settle(id, if (interrupted) "INTERRUPTED" else "SETTLED", tokens, millis) == 1) {
            "reservation is not pending"
        }
    }
}
