package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The stored CUSTOM permission DRAFT of ONE session (HXA-209 D, ADR-PERMISSIONS-001 section 4):
 * the user's copied-then-edited rule snapshot, kept SEPARATE from the active compiled config so
 * it survives a switch to a preset (the draft goes INACTIVE, not deleted) and is restored when
 * CUSTOM is re-selected.
 *
 * [sourcePreset] is the preset the snapshot was copied from — a provenance label for the UI
 * ("custom based on WORKSPACE"), never a rule; it is one of FULL_ACCESS / WORKSPACE / READ_ONLY,
 * never CUSTOM (a draft is copied from a preset, not from another custom). [rulesJson] is the
 * deterministic flat object from
 * [com.helix.core.storage.repository.SessionPermissionRulesCodec]; [configVersion] is the
 * rule-set contract version the draft was written with.
 *
 * This row is a UI concern: the dispatcher's live read
 * ([com.helix.core.storage.repository.SessionPermissionConfigRepository.forSession]) returns
 * only the ACTIVE config and never sees the draft, so the draft is not part of the execution
 * linearization and has no [revision] — its only timestamps are the user change times.
 */
@Entity(
    tableName = "session_permission_drafts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionPermissionDraftEntity(
    @PrimaryKey val sessionId: String,
    val sourcePreset: String,
    val rulesJson: String,
    val configVersion: Int,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)
