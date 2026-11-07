package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * ADR-AGENT-001 section 3: `tool_call_reviews` — immutable user review decisions for parked tool calls.
 *
 * Each row records a closed human review decision for an uncertain tool call ([ToolCallEntity]).
 * The parent [ToolCallEntity] is referenced via foreign key with CASCADE deletion.
 */
@Entity(
    tableName = "tool_call_reviews",
    foreignKeys =
        [
            ForeignKey(
                entity = ToolCallEntity::class,
                parentColumns = ["id"],
                childColumns = ["toolCallId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class ToolCallReviewEntity(
    @PrimaryKey val toolCallId: String,
    val decision: String,
    val reviewedAt: Long,
)
