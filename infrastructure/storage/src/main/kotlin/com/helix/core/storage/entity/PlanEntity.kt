package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Lifecycle/approval facts and a rebuildable display index; editable content lives in private JSON files. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val version: Int,
    val hash: String,
    val state: String,
    val evidenceRef: String?,
    /** Immutable evidence captured in the same transaction as approval, never a second editable body. */
    val approvedSnapshot: String? = null,
)
