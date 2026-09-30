package com.helix.core.storage.repository

import com.helix.core.model.TurnState
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnRepositoryTest {
    @Test
    fun `stale snapshot cannot overwrite a newer durable state`() {
        val dao = FakeTurnDao()
        val repository = TurnRepository(dao)
        val created = repository.start("turn", "session", 10)
        val building = repository.updateState(created, TurnState.BUILDING_CONTEXT, 0, null, null)

        val waiting = repository.updateState(building, TurnState.WAITING_MODEL, 0, null, null)
        repository.updateState(waiting, TurnState.RECEIVING_MODEL, 0, null, null)

        assertThrows(IllegalStateException::class.java) {
            repository.updateState(waiting, TurnState.CANCELLING, 0, null, null)
        }
        assertEquals(TurnState.RECEIVING_MODEL.name, repository.resolve("turn").state)
    }

    @Test
    fun `same-state update still requires the snapshot to be current`() {
        val dao = FakeTurnDao()
        val repository = TurnRepository(dao)
        val created = repository.start("turn", "session", 10)
        val building = repository.updateState(created, TurnState.BUILDING_CONTEXT, 0, null, null)

        val refreshed = repository.updateState(building, TurnState.BUILDING_CONTEXT, 1, null, null)

        assertEquals(1, refreshed.stepCount)
        assertThrows(IllegalStateException::class.java) {
            repository.updateState(building, TurnState.BUILDING_CONTEXT, 2, null, null)
        }
        assertEquals(1, repository.resolve("turn").stepCount)
    }

    private class FakeTurnDao : TurnDao {
        private val rows = linkedMapOf<String, TurnEntity>()

        override fun pendingTasks(): List<TurnEntity> = emptyList()

        override fun recent(limit: Int): List<TurnEntity> = rows.values.toList().takeLast(limit)

        override fun collectResult(
            id: String,
            now: Long,
        ): Int = 0

        override fun requestPause(
            id: String,
            now: Long,
        ): Int = 0

        override fun insert(turn: TurnEntity) {
            check(rows.putIfAbsent(turn.id, turn) == null)
        }

        override fun byId(id: String): TurnEntity? = rows[id]

        override fun byClientRequestId(clientRequestId: String): TurnEntity? =
            rows.values.firstOrNull { it.clientRequestId == clientRequestId }

        override fun listBySession(sessionId: String): List<TurnEntity> =
            rows.values.filter { it.sessionId == sessionId }

        override fun latestForUnarchivedSessions(): List<TurnEntity> =
            rows.values
                .sortedBy { it.startedAt }
                .groupBy { it.sessionId }
                .values
                .map { it.last() }

        override fun listActive(): List<TurnEntity> = rows.values.filter { !TurnState.valueOf(it.state).isTerminal }

        override fun updateState(
            id: String,
            expectedState: String,
            expectedStepCount: Int,
            state: String,
            stepCount: Int,
            endedAt: Long?,
            errorCode: String?,
        ): Int {
            val current = rows[id]
            return if (current != null && current.state == expectedState && current.stepCount == expectedStepCount) {
                rows[id] = current.copy(state = state, stepCount = stepCount, endedAt = endedAt, errorCode = errorCode)
                1
            } else {
                0
            }
        }
    }
}
