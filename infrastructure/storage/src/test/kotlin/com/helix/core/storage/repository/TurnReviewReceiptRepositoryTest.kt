package com.helix.core.storage.repository

import com.helix.core.storage.dao.TurnReviewReceiptDao
import com.helix.core.storage.entity.TurnReviewReceiptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnReviewReceiptRepositoryTest {
    private val dao = FakeTurnReviewReceiptDao()
    private val repository = TurnReviewReceiptRepository(dao)

    @Test
    fun `same action is idempotent and a different action conflicts`() {
        val first = repository.claim("turn-1", "action-1", "a".repeat(64))
        val duplicate = repository.claim("turn-1", "action-1", "a".repeat(64))

        assertEquals(first, duplicate)
        assertEquals("action-1", first.clientActionId)
        assertThrows(IllegalStateException::class.java) {
            repository.claim("turn-1", "action-2", "b".repeat(64))
        }
    }

    @Test
    fun `losing insert race resolves the durable winner`() {
        val winner = TurnReviewReceiptEntity("turn-1", "action-1", "a".repeat(64))
        dao.raceWinner = winner

        val resolved = repository.claim("turn-1", "action-1", "a".repeat(64))

        assertEquals(winner, resolved)
    }

    @Test
    fun `malformed command identity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.claim("", "action-1", "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.claim("turn-1", "", "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.claim("turn-1", "action-1", "not-a-fingerprint")
        }
    }

    private class FakeTurnReviewReceiptDao : TurnReviewReceiptDao {
        private val rows = mutableMapOf<String, TurnReviewReceiptEntity>()
        var raceWinner: TurnReviewReceiptEntity? = null

        override fun insertIfAbsent(receipt: TurnReviewReceiptEntity): Long {
            val winner = raceWinner
            return when {
                winner != null -> {
                    rows.putIfAbsent(winner.turnId, winner)
                    raceWinner = null
                    -1
                }

                receipt.turnId in rows -> {
                    -1
                }

                else -> {
                    rows[receipt.turnId] = receipt
                    1
                }
            }
        }

        override fun byTurn(turnId: String): TurnReviewReceiptEntity? = rows[turnId]
    }
}
