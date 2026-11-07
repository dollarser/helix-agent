package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.TurnReviewReceiptEntity

@Dao
interface TurnReviewReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(receipt: TurnReviewReceiptEntity): Long

    @Query("SELECT * FROM turn_review_receipts WHERE turnId = :turnId")
    fun byTurn(turnId: String): TurnReviewReceiptEntity?
}
