package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Immutable Turn-level receipt for one completed effect-review command.
 *
 * Per-call decisions live in tool_call_reviews. This row exists only to make the user command
 * idempotent/conflict-detectable without mutating the Turn runtime snapshot.
 */
@Entity(
    tableName = "turn_review_receipts",
    foreignKeys =
        [
            ForeignKey(
                entity = TurnEntity::class,
                parentColumns = ["id"],
                childColumns = ["turnId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class TurnReviewReceiptEntity(
    @PrimaryKey val turnId: String,
    val clientActionId: String,
    val actionFingerprint: String,
)
