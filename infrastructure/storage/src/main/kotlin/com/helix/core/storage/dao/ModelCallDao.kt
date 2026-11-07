package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ModelCallEntity

@Dao
interface ModelCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(call: ModelCallEntity)

    @Query("SELECT * FROM model_calls WHERE id = :id")
    fun byId(id: String): ModelCallEntity?

    @Query("SELECT * FROM model_calls WHERE turnId = :turnId ORDER BY ordinal ASC")
    fun listByTurn(turnId: String): List<ModelCallEntity>

    @Query("SELECT COALESCE(MAX(ordinal), -1) FROM model_calls WHERE turnId = :turnId")
    fun maxOrdinal(turnId: String): Long

    @Query(
        "UPDATE model_calls SET toolMessageId = :messageId WHERE id = :id AND toolMessageId IS NULL " +
            "AND EXISTS (SELECT 1 FROM messages m WHERE m.id = :messageId AND m.turnId = model_calls.turnId " +
            "AND m.kind = 'TOOL_CALLS' AND m.role = 'ASSISTANT')",
    )
    fun bindToolMessage(
        id: String,
        messageId: String,
    ): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM model_calls WHERE turnId = :turnId AND id != :modelCallId AND state = 'RUNNING')",
    )
    fun hasOtherRunning(
        turnId: String,
        modelCallId: String,
    ): Boolean

    @Query(
        "UPDATE model_calls SET state = 'INTERRUPTED', settledAt = :timestamp WHERE state = 'RUNNING' " +
            "AND turnId IN (SELECT id FROM turns WHERE state = 'INTERRUPTED')",
    )
    fun interruptForInterruptedTurns(timestamp: Long): Int

    @Query(
        "UPDATE model_calls SET state = :state, usage = :usage, requestId = :requestId, " +
            "settledAt = :settledAt WHERE id = :id",
    )
    fun update(
        id: String,
        state: String,
        usage: String?,
        requestId: String?,
        settledAt: Long?,
    )

    @Query("UPDATE model_calls SET promptFingerprint = :fingerprint, promptSections = :sections WHERE id = :id")
    fun recordPrompt(
        id: String,
        fingerprint: String,
        sections: String,
    )

    @Query("UPDATE model_calls SET requestManifest = :manifest WHERE id = :id")
    fun recordRequestManifest(
        id: String,
        manifest: String,
    )
}
