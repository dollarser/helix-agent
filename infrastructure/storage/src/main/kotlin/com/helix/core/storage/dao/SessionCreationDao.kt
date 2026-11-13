package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionCreationGrantEntity
import com.helix.core.storage.entity.SessionCreationReceiptEntity

@Dao
interface SessionCreationDao {
    @Query("SELECT * FROM session_creation_grants WHERE sourceSessionId = :source")
    fun grant(source: String): SessionCreationGrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveGrant(grant: SessionCreationGrantEntity)

    @Query("SELECT * FROM session_creation_receipts WHERE sourceSessionId = :source AND requestKey = :key")
    fun receipt(
        source: String,
        key: String,
    ): SessionCreationReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(receipt: SessionCreationReceiptEntity)
}
