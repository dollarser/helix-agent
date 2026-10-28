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

    @Test
    fun `admission excludes settled history but retains review-independent and unknown blockers`() {
        val dao = FakeTurnDao()
        val repository = TurnRepository(dao)
        val template = repository.start("template", "other", 0)
        TurnState.entries.forEachIndexed { index, state ->
            dao.insert(
                template.copy(id = state.name, sessionId = "session", state = state.name, startedAt = index.toLong()),
            )
        }
        dao.insert(template.copy(id = "unknown", sessionId = "session", state = "UNRECOGNIZED"))
        val expected =
            TurnState.entries
                .filter { !it.isTerminal && it != TurnState.NEEDS_REVIEW }
                .map { it.name }
                .toSet()
        assertEquals(expected + "unknown", repository.admissionBlockers("session").map { it.id }.toSet())
        assertEquals(emptyList<TurnEntity>(), repository.byIds(emptyList()))
        assertEquals(listOf("FAILED"), repository.byIds(listOf("FAILED", "FAILED", "missing")).map { it.id })
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

        override fun byIds(ids: List<String>): List<TurnEntity> = rows.values.filter { it.id in ids }

        override fun unsettled(
            sessionId: String,
            settledStates: List<String>,
        ): List<TurnEntity> = listBySession(sessionId).filter { it.state !in settledStates }

        override fun latestVisible(
            sessionId: String,
            state: String?,
        ): TurnEntity? = listBySession(sessionId).lastOrNull { state == null || it.state == state }

        override fun recentByStates(
            sessionId: String,
            states: List<String>,
            limit: Int,
        ): List<TurnEntity> =
            listBySession(sessionId)
                .sortedBy { it.startedAt }
                .filter { it.state in states }
                .takeLast(limit)
                .reversed()

        override fun hasLaterCompleted(turnId: String): Boolean {
            val original = requireNotNull(byId(turnId))
            val ordered = listBySession(original.sessionId).sortedBy { it.startedAt }
            return ordered.drop(ordered.indexOfFirst { it.id == turnId } + 1).any { it.state == "COMPLETED" }
        }

        override fun ledgerCandidates(
            sessionId: String,
            offset: Int,
            limit: Int,
        ): List<TurnEntity> = error("No ledger tool calls in this fixture")

        override fun budgetContinuationClear(turnId: String): Boolean = error("No budget continuation in this fixture")

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
