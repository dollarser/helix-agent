package com.helix.core.storage.repository

import com.helix.core.model.TurnState
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.TurnEntity

class TurnRepository(
    private val dao: TurnDao,
) {
    fun start(
        id: String,
        sessionId: String,
        startedAt: Long,
    ): TurnEntity {
        require(startedAt >= 0) { "startedAt must be >= 0" }
        val entity = TurnEntity(id, sessionId, TurnState.CREATED.name, 0, startedAt, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): TurnEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("turn not found: $id")
    }

    fun listBySession(sessionId: String): List<TurnEntity> = dao.listBySession(sessionId)

    /** Non-terminal turns left by a previous process — the HXA-015 recovery scan. */
    fun listActive(): List<TurnEntity> = dao.listActive()

    fun pendingTasks(): List<TurnEntity> = dao.pendingTasks()

    fun recent(limit: Int = 200): List<TurnEntity> {
        require(limit in 1..1000)
        return dao.recent(limit)
    }

    fun collectResult(
        id: String,
        now: Long,
    ): Boolean = dao.collectResult(id, now) == 1

    fun requestPause(
        id: String,
        now: Long,
    ): Boolean = dao.requestPause(id, now) == 1

    /** [TurnState] name is validated before it hits the column. */
    fun updateState(
        turn: TurnEntity,
        state: TurnState,
        stepCount: Int,
        endedAt: Long?,
        errorCode: String?,
    ): TurnEntity {
        require(stepCount >= turn.stepCount) { "stepCount must never decrease" }
        endedAt?.let { require(it >= turn.startedAt) { "endedAt must be >= startedAt" } }
        val current = TurnState.valueOf(turn.state)
        val valid =
            current == state ||
                current.canTransitionTo(state) ||
                (state == TurnState.INTERRUPTED && current.canBecomeInterruptedOnProcessDeath())
        require(valid) { "illegal turn transition $current -> $state" }
        dao.updateState(turn.id, state.name, stepCount, endedAt, errorCode)
        return turn.copy(state = state.name, stepCount = stepCount, endedAt = endedAt, errorCode = errorCode)
    }
}
