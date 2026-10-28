package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Reusable user-authored profiles. Session and Turn snapshots are independent copies. */
@Entity(tableName = "experts")
data class ExpertEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val instruction: String,
)
