package com.helix.core.storage.repository

import com.helix.core.storage.dao.TurnReviewReceiptDao
import com.helix.core.storage.entity.TurnReviewReceiptEntity

/** Immutable exact-once receipt for a completed Turn review command. */
class TurnReviewReceiptRepository(
    private val dao: TurnReviewReceiptDao,
) {
    fun find(turnId: String): TurnReviewReceiptEntity? {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        return dao.byTurn(turnId)
    }

    fun claim(
        turnId: String,
        clientActionId: String,
        actionFingerprint: String,
    ): TurnReviewReceiptEntity {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(clientActionId.isNotBlank()) { "review action id must not be blank" }
        require(actionFingerprint.matches(SHA256_HEX)) {
            "review action fingerprint must be sha256 hex"
        }
        val existing = find(turnId)
        val winner =
            if (existing != null) {
                existing
            } else {
                val receipt = TurnReviewReceiptEntity(turnId, clientActionId, actionFingerprint)
                if (dao.insertIfAbsent(receipt) != -1L) {
                    receipt
                } else {
                    checkNotNull(find(turnId)) {
                        "review receipt insert was ignored but no durable winner exists: $turnId"
                    }
                }
            }
        return requireSame(winner, clientActionId, actionFingerprint)
    }

    private fun requireSame(
        existing: TurnReviewReceiptEntity,
        clientActionId: String,
        actionFingerprint: String,
    ): TurnReviewReceiptEntity {
        check(
            existing.clientActionId == clientActionId &&
                existing.actionFingerprint == actionFingerprint,
        ) {
            "REVIEW_RESOLUTION_CONFLICT: turn ${existing.turnId} already resolved by ${existing.clientActionId}"
        }
        return existing
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
