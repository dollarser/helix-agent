package com.helix.core.storage.repository

import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolCallReviewDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolCallReviewEntity
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolCallReviewRepositoryTest {
    private lateinit var reviewDao: FakeToolCallReviewDao
    private lateinit var toolCallDao: FakeToolCallDao
    private lateinit var turnDao: FakeTurnDao
    private lateinit var repository: ToolCallReviewRepository

    @Before
    fun setUp() {
        reviewDao = FakeToolCallReviewDao()
        toolCallDao = FakeToolCallDao()
        reviewDao.toolCallsLookup = { toolCallDao.byId(it) }
        turnDao = FakeTurnDao()
        repository = ToolCallReviewRepository(reviewDao, toolCallDao, turnDao)
    }

    @Test
    fun `first review decision inserts successfully`() {
        seedTurn("turn-1", "NEEDS_REVIEW")
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)

        val review =
            repository.review(
                toolCallId = "call-1",
                decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
                reviewedAt = 1000L,
            )

        assertEquals("call-1", review.toolCallId)
        assertEquals(ToolEffectReviewDecision.CONFIRMED_APPLIED.name, review.decision)
        assertEquals(1000L, review.reviewedAt)
        assertEquals(review, repository.findByToolCallId("call-1"))
    }

    @Test
    fun `duplicate review with same decision is idempotent`() {
        seedTurn("turn-1", TurnState.INTERRUPTED.name)
        seedToolCall("call-1", "turn-1", ToolCallState.INTERRUPTED.name)

        val first =
            repository.review(
                toolCallId = "call-1",
                decision = ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED,
                reviewedAt = 1000L,
            )

        val second =
            repository.review(
                toolCallId = "call-1",
                decision = ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED,
                reviewedAt = 2000L,
            )

        assertEquals(first, second)
        assertEquals(1000L, second.reviewedAt)
        assertEquals(1, reviewDao.all().size)
    }

    @Test
    fun `different review decision on same call throws conflict`() {
        seedTurn("turn-1", "NEEDS_REVIEW")
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)

        repository.review(
            toolCallId = "call-1",
            decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
            reviewedAt = 1000L,
        )

        val exception =
            assertThrows(IllegalStateException::class.java) {
                repository.review(
                    toolCallId = "call-1",
                    decision = ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN,
                    reviewedAt = 1500L,
                )
            }

        assertTrue(
            "Conflict error message should start with REVIEW_DECISION_CONFLICT, was: ${exception.message}",
            exception.message?.startsWith("REVIEW_DECISION_CONFLICT") == true,
        )
    }

    @Test
    fun `same decision racing another first writer resolves idempotently from durable row`() {
        seedTurn("turn-1", "NEEDS_REVIEW")
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)
        reviewDao.raceWinner =
            ToolCallReviewEntity(
                "call-1",
                ToolEffectReviewDecision.CONFIRMED_APPLIED.name,
                900L,
            )

        val review =
            repository.review(
                "call-1",
                ToolEffectReviewDecision.CONFIRMED_APPLIED,
                1000L,
            )

        assertEquals(900L, review.reviewedAt)
        assertEquals(1, reviewDao.all().size)
    }

    @Test
    fun `conflicting decision racing another first writer returns stable conflict`() {
        seedTurn("turn-1", "NEEDS_REVIEW")
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)
        reviewDao.raceWinner =
            ToolCallReviewEntity(
                "call-1",
                ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED.name,
                900L,
            )

        val exception =
            assertThrows(IllegalStateException::class.java) {
                repository.review(
                    "call-1",
                    ToolEffectReviewDecision.CONFIRMED_APPLIED,
                    1000L,
                )
            }

        assertTrue(exception.message?.startsWith("REVIEW_DECISION_CONFLICT") == true)
        assertEquals(1, reviewDao.all().size)
    }

    @Test
    fun `review rejects tool call in non-parked state`() {
        seedTurn("turn-1", "NEEDS_REVIEW")

        val invalidStates =
            listOf(
                ToolCallState.PENDING.name,
                ToolCallState.AWAITING_APPROVAL.name,
                ToolCallState.RUNNING.name,
                ToolCallState.COMPLETED.name,
                ToolCallState.FAILED.name,
                ToolCallState.CANCELLED.name,
                ToolCallState.DENIED.name,
            )

        for (state in invalidStates) {
            val callId = "call-$state"
            seedToolCall(callId, "turn-1", state)
            val exception =
                assertThrows(IllegalStateException::class.java) {
                    repository.review(
                        toolCallId = callId,
                        decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
                        reviewedAt = 1000L,
                    )
                }
            assertTrue(
                "Should reject non-parked state $state: ${exception.message}",
                exception.message?.contains("NEEDS_REVIEW or INTERRUPTED") == true,
            )
        }
    }

    @Test
    fun `review rejects missing tool call`() {
        seedTurn("turn-1", "NEEDS_REVIEW")

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                repository.review(
                    toolCallId = "non-existent-call",
                    decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
                    reviewedAt = 1000L,
                )
            }
        assertTrue(exception.message?.contains("tool call not found") == true)
    }

    @Test
    fun `review rejects tool call whose parent turn is in invalid state`() {
        seedTurn("turn-1", TurnState.COMPLETED.name)
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)

        val exception =
            assertThrows(IllegalStateException::class.java) {
                repository.review(
                    toolCallId = "call-1",
                    decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
                    reviewedAt = 1000L,
                )
            }
        assertTrue(
            "Should reject invalid parent turn state: ${exception.message}",
            exception.message?.contains("turn") == true &&
                exception.message?.contains("NEEDS_REVIEW or INTERRUPTED") == true,
        )
    }

    @Test
    fun `review rejects tool call whose parent turn does not exist`() {
        seedToolCall("call-1", "missing-turn", ToolCallState.NEEDS_REVIEW.name)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                repository.review(
                    toolCallId = "call-1",
                    decision = ToolEffectReviewDecision.CONFIRMED_APPLIED,
                    reviewedAt = 1000L,
                )
            }
        assertTrue(exception.message?.contains("turn not found") == true)
    }

    @Test
    fun `unresolved effect remains until every uncertain call has an immutable review`() {
        seedTurn("turn-1", TurnState.INTERRUPTED.name)
        seedToolCall("call-1", "turn-1", ToolCallState.INTERRUPTED.name)
        seedToolCall("call-2", "turn-1", ToolCallState.NEEDS_REVIEW.name)
        seedToolCall("done", "turn-1", ToolCallState.COMPLETED.name)

        assertEquals(listOf("call-1", "call-2"), repository.unresolvedToolCallIds("turn-1"))
        assertTrue(repository.hasUnresolvedEffects("turn-1"))

        repository.review("call-1", ToolEffectReviewDecision.CONFIRMED_APPLIED, 1000L)
        assertEquals(listOf("call-2"), repository.unresolvedToolCallIds("turn-1"))
        repository.review("call-2", ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN, 1001L)

        assertTrue(repository.unresolvedToolCallIds("turn-1").isEmpty())
        assertTrue(!repository.hasUnresolvedEffects("turn-1"))
    }

    @Test
    fun `listByTurn returns reviews for all calls belonging to turn`() {
        seedTurn("turn-1", "NEEDS_REVIEW")
        seedToolCall("call-1", "turn-1", ToolCallState.NEEDS_REVIEW.name)
        seedToolCall("call-2", "turn-1", ToolCallState.NEEDS_REVIEW.name)

        seedTurn("turn-2", "NEEDS_REVIEW")
        seedToolCall("call-3", "turn-2", ToolCallState.NEEDS_REVIEW.name)

        repository.review("call-1", ToolEffectReviewDecision.CONFIRMED_APPLIED, 1000L)
        repository.review("call-2", ToolEffectReviewDecision.CONFIRMED_NOT_APPLIED, 1001L)
        repository.review("call-3", ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN, 1002L)

        val turn1Reviews = repository.listByTurn("turn-1")
        assertEquals(2, turn1Reviews.size)
        val turn1CallIds = turn1Reviews.map { it.toolCallId }.toSet()
        assertEquals(setOf("call-1", "call-2"), turn1CallIds)

        val turn2Reviews = repository.listByTurn("turn-2")
        assertEquals(1, turn2Reviews.size)
        assertEquals("call-3", turn2Reviews.first().toolCallId)
    }

    private fun seedTurn(
        id: String,
        state: String,
    ) {
        turnDao.insert(
            TurnEntity(
                id = id,
                sessionId = "session-1",
                state = state,
                stepCount = 1,
                startedAt = 0L,
                endedAt = null,
                errorCode = null,
            ),
        )
    }

    private fun seedToolCall(
        id: String,
        turnId: String,
        state: String,
    ) {
        toolCallDao.insert(
            ToolCallEntity(
                id = id,
                turnId = turnId,
                callId = "call-$id",
                name = "test_tool",
                version = "1.0",
                argsJson = "{}",
                argsHash = "hash",
                state = state,
            ),
        )
    }

    private class FakeToolCallReviewDao : ToolCallReviewDao {
        private val reviews = mutableMapOf<String, ToolCallReviewEntity>()
        var toolCallsLookup: ((String) -> ToolCallEntity?)? = null
        var raceWinner: ToolCallReviewEntity? = null

        fun all(): List<ToolCallReviewEntity> = reviews.values.toList()

        override fun insertIfAbsent(review: ToolCallReviewEntity): Long {
            raceWinner?.let { winner ->
                raceWinner = null
                reviews.putIfAbsent(winner.toolCallId, winner)
            }
            if (review.toolCallId in reviews) return -1L
            reviews[review.toolCallId] = review
            return 1L
        }

        override fun byToolCallId(toolCallId: String): ToolCallReviewEntity? = reviews[toolCallId]

        override fun byToolCallIds(toolCallIds: List<String>): List<ToolCallReviewEntity> =
            toolCallIds.mapNotNull { reviews[it] }

        override fun listByTurn(turnId: String): List<ToolCallReviewEntity> {
            val lookup = toolCallsLookup ?: return emptyList()
            return reviews.values.filter { review ->
                val call = lookup(review.toolCallId)
                call?.turnId == turnId
            }
        }
    }

    private class FakeToolCallDao : ToolCallDao {
        private val calls = mutableMapOf<String, ToolCallEntity>()

        override fun unsettledUnderTerminalTurns(): List<ToolCallEntity> = emptyList()

        override fun insert(call: ToolCallEntity) {
            calls[call.id] = call
        }

        override fun byId(id: String): ToolCallEntity? = calls[id]

        override fun listByTurn(turnId: String): List<ToolCallEntity> = calls.values.filter { it.turnId == turnId }

        override fun byTurnAndCallId(
            turnId: String,
            callId: String,
        ): ToolCallEntity? = calls.values.firstOrNull { it.turnId == turnId && it.callId == callId }

        override fun detachedJobCandidates(recentLimit: Int): List<ToolCallEntity> = emptyList()

        override fun updateState(
            id: String,
            state: String,
        ) {
            calls[id]?.let { calls[id] = it.copy(state = state) }
        }
    }

    private class FakeTurnDao : TurnDao {
        private val turns = mutableMapOf<String, TurnEntity>()

        override fun pendingTasks(): List<TurnEntity> = emptyList()

        override fun recent(limit: Int): List<TurnEntity> = emptyList()

        override fun collectResult(
            id: String,
            now: Long,
        ): Int = 0

        override fun requestPause(
            id: String,
            now: Long,
        ): Int = 0

        override fun insert(turn: TurnEntity) {
            turns[turn.id] = turn
        }

        override fun byId(id: String): TurnEntity? = turns[id]

        override fun byClientRequestId(clientRequestId: String): TurnEntity? =
            turns.values.firstOrNull { it.clientRequestId == clientRequestId }

        override fun listBySession(sessionId: String): List<TurnEntity> =
            turns.values.filter { it.sessionId == sessionId }

        override fun listActive(): List<TurnEntity> = emptyList()

        override fun updateState(
            id: String,
            expectedState: String,
            expectedStepCount: Int,
            state: String,
            stepCount: Int,
            endedAt: Long?,
            errorCode: String?,
        ): Int {
            val current = turns[id]
            return if (current != null && current.state == expectedState && current.stepCount == expectedStepCount) {
                turns[id] = current.copy(state = state, stepCount = stepCount, endedAt = endedAt, errorCode = errorCode)
                1
            } else {
                0
            }
        }
    }
}
