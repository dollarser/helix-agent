package com.helix.app.review

import com.helix.core.model.Clock
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolCallReviewDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolCallReviewEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.ToolCallRepository
import com.helix.core.storage.repository.ToolCallReviewRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ToolEffectReviewServiceTest {
    private lateinit var toolCalls: FakeToolCallDao
    private lateinit var reviews: FakeToolCallReviewDao
    private lateinit var turns: FakeTurnDao
    private lateinit var service: ToolEffectReviewService

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(1_000L)
        }

    @Before
    fun setUp() {
        toolCalls = FakeToolCallDao()
        reviews = FakeToolCallReviewDao()
        turns = FakeTurnDao()
        reviews.toolCallLookup = toolCalls::byId
        service =
            ToolEffectReviewService(
                ToolCallRepository(toolCalls),
                ToolCallReviewRepository(reviews, toolCalls, turns),
                clock = clock,
            )
    }

    @Test
    fun `review status aggregates deterministic and acknowledged-unknown decisions`() {
        val deterministic =
            ReviewItemUi(
                "c1",
                "t1",
                "local-1",
                "write",
                "{}",
                ToolCallState.NEEDS_REVIEW,
                ToolEffectReviewDecision.CONFIRMED_APPLIED,
                1_000,
            )
        val unknown =
            deterministic.copy(
                toolCallId = "c2",
                existingDecision = ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN,
            )

        val complete = TurnReviewStatus("t1", listOf(deterministic))
        assertTrue(complete.isComplete)
        assertTrue(complete.canResolve)
        assertFalse(complete.hasAcknowledgedUnknown)

        val acknowledged = TurnReviewStatus("t1", listOf(deterministic, unknown))
        assertTrue(acknowledged.isComplete)
        assertTrue(acknowledged.canResolve)
        assertTrue(acknowledged.hasAcknowledgedUnknown)
    }

    @Test
    fun `empty review status is not a NEEDS_REVIEW completion signal`() {
        val status = TurnReviewStatus("t1", emptyList())
        assertFalse(status.isComplete)
        assertFalse(status.canResolve)
        assertEquals(0, status.pendingReviewCount)
    }

    @Test
    fun `load status includes only uncertain calls and joins immutable decisions`() {
        seedTurn("turn", TurnState.NEEDS_REVIEW)
        seedCall("done", "turn", ToolCallState.COMPLETED)
        seedCall("review", "turn", ToolCallState.NEEDS_REVIEW)
        seedCall("interrupted", "turn", ToolCallState.INTERRUPTED)
        service.submitReview("review", ToolEffectReviewDecision.CONFIRMED_APPLIED)

        val status = service.loadReviewStatus("turn")

        assertEquals(listOf("review", "interrupted"), status.items.map { it.toolCallId })
        assertEquals(ToolEffectReviewDecision.CONFIRMED_APPLIED, status.items[0].existingDecision)
        assertEquals(null, status.items[1].existingDecision)
        assertEquals(1, status.pendingReviewCount)
    }

    @Test
    fun `submit review is idempotent and conflict stays stable`() {
        seedTurn("turn", TurnState.NEEDS_REVIEW)
        seedCall("review", "turn", ToolCallState.NEEDS_REVIEW)

        val first = service.submitReview("review", ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED)
        val duplicate = service.submitReview("review", ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED)
        assertEquals(first, duplicate)

        val error =
            assertThrows(IllegalStateException::class.java) {
                service.submitReview("review", ToolEffectReviewDecision.CONFIRMED_APPLIED)
            }
        assertTrue(error.message?.startsWith("REVIEW_DECISION_CONFLICT") == true)
    }

    @Test
    fun `corrupt stored decision fails closed instead of disappearing from presentation`() {
        seedTurn("turn", TurnState.NEEDS_REVIEW)
        seedCall("review", "turn", ToolCallState.NEEDS_REVIEW)
        reviews.rows["review"] = ToolCallReviewEntity("review", "CORRUPT", 1_000)

        assertThrows(IllegalArgumentException::class.java) {
            service.loadReviewStatus("turn")
        }
    }

    private fun seedTurn(
        id: String,
        state: TurnState,
    ) {
        turns.insert(
            TurnEntity(
                id = id,
                sessionId = "session",
                state = state.name,
                stepCount = 1,
                startedAt = 1,
                endedAt = null,
                errorCode = null,
            ),
        )
    }

    private fun seedCall(
        id: String,
        turnId: String,
        state: ToolCallState,
    ) {
        toolCalls.insert(
            ToolCallEntity(
                id = id,
                turnId = turnId,
                callId = id,
                name = "tool.test",
                version = "1",
                argsJson = "{}",
                argsHash = "hash",
                state = state.name,
            ),
        )
    }

    private class FakeToolCallDao : ToolCallDao {
        private val rows = linkedMapOf<String, ToolCallEntity>()

        override fun unsettledUnderTerminalTurns(): List<ToolCallEntity> = emptyList()

        override fun insert(call: ToolCallEntity) {
            rows[call.id] = call
        }

        override fun byId(id: String): ToolCallEntity? = rows[id]

        override fun listByTurn(turnId: String): List<ToolCallEntity> = rows.values.filter { it.turnId == turnId }

        override fun recentByTurn(
            turnId: String,
            limit: Int,
        ) = listByTurn(turnId).takeLast(limit).reversed()

        override fun byTurnAndCallId(
            turnId: String,
            callId: String,
        ): ToolCallEntity? = rows.values.firstOrNull { it.turnId == turnId && it.callId == callId }

        override fun detachedJobCandidates(recentLimit: Int): List<ToolCallEntity> = emptyList()

        override fun updateState(
            id: String,
            state: String,
        ) {
            rows[id]?.let { rows[id] = it.copy(state = state) }
        }
    }

    private class FakeToolCallReviewDao : ToolCallReviewDao {
        val rows = linkedMapOf<String, ToolCallReviewEntity>()
        var toolCallLookup: (String) -> ToolCallEntity? = { null }

        override fun insertIfAbsent(review: ToolCallReviewEntity): Long {
            if (review.toolCallId in rows) return -1
            rows[review.toolCallId] = review
            return 1
        }

        override fun byToolCallId(toolCallId: String): ToolCallReviewEntity? = rows[toolCallId]

        override fun byToolCallIds(toolCallIds: List<String>): List<ToolCallReviewEntity> =
            toolCallIds.mapNotNull(rows::get)

        override fun listByTurn(turnId: String): List<ToolCallReviewEntity> =
            rows.values.filter { toolCallLookup(it.toolCallId)?.turnId == turnId }
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
            rows[turn.id] = turn
        }

        override fun byId(id: String): TurnEntity? = rows[id]

        override fun byClientRequestId(clientRequestId: String): TurnEntity? =
            rows.values.firstOrNull { it.clientRequestId == clientRequestId }

        override fun listBySession(sessionId: String): List<TurnEntity> =
            rows.values.filter { it.sessionId == sessionId }

        // This fixture has only unarchived sessions; preserve insertion order for timestamp ties.
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
