package com.helix.core.storage.dao

import androidx.room.Query

/** User metadata CAS statements inherited by the scoped access DAO; no separate database owner. */
interface UserSessionMetadataCommands {
    @Query(
        "UPDATE sessions SET title = :title, searchTitle = :searchTitle WHERE id = :target AND title = :expectedTitle",
    )
    fun userRename(
        target: String,
        expectedTitle: String,
        title: String,
        searchTitle: String,
    ): Int

    @Query(
        "UPDATE sessions SET archivedAt = :archivedAt WHERE id = :target " +
            "AND (archivedAt IS NOT NULL) = :expectedArchived",
    )
    fun userArchive(
        target: String,
        expectedArchived: Boolean,
        archivedAt: Long?,
    ): Int
}
