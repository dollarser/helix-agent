package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.helix.core.storage.entity.ComposerDraftEntity

@Dao
abstract class ComposerDraftDao {
    @Query("SELECT * FROM composer_drafts WHERE sessionId = :sessionId")
    abstract fun get(sessionId: String): ComposerDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun put(draft: ComposerDraftEntity)

    @Transaction
    @Suppress("ReturnCount") // Idempotent save, stale revision and successful CAS are separate outcomes.
    open fun save(
        draft: ComposerDraftEntity,
        expectedRevision: Long?,
    ): Boolean {
        val old = get(draft.sessionId)
        if (old == draft) return true
        if (old?.revision != expectedRevision) return false
        require(draft.revision == (expectedRevision?.plus(1) ?: 0L)) { "DRAFT_REVISION_CONFLICT" }
        require(old?.clientRequestId != draft.clientRequestId) { "DRAFT_REQUEST_ID_REUSED" }
        put(draft)
        return true
    }

    @Query(
        """DELETE FROM composer_drafts WHERE sessionId = :sessionId
        AND revision = :revision AND clientRequestId = :requestId""",
    )
    abstract fun clear(
        sessionId: String,
        revision: Long,
        requestId: String,
    ): Int
}
