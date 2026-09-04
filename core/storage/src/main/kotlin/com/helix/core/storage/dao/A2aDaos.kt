package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.helix.core.storage.entity.A2aAgentEntity
import com.helix.core.storage.entity.A2aCapabilityEntity
import com.helix.core.storage.entity.A2aTaskEntity

@Dao
interface A2aAgentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(agent: A2aAgentEntity)

    @Query("SELECT * FROM a2a_agents WHERE id = :id")
    fun byId(id: String): A2aAgentEntity?

    @Query("SELECT * FROM a2a_agents ORDER BY rowid ASC")
    fun list(): List<A2aAgentEntity>

    @Query("UPDATE a2a_agents SET enabled = :enabled WHERE id = :id")
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Int
}

@Dao
interface A2aCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(capabilities: List<A2aCapabilityEntity>)

    @Query("SELECT * FROM a2a_capabilities WHERE agentId = :agentId ORDER BY rowId ASC")
    fun listByAgent(agentId: String): List<A2aCapabilityEntity>

    @Query("DELETE FROM a2a_capabilities WHERE agentId = :agentId")
    fun deleteByAgent(agentId: String)

    @Query("UPDATE a2a_capabilities SET enabled = :enabled WHERE rowId = :rowId")
    fun setEnabled(
        rowId: Long,
        enabled: Boolean,
    ): Int

    @Query(
        "UPDATE a2a_agents SET cardHash = :cardHash, " +
            "enabled = CASE WHEN :disableAgent THEN 0 ELSE enabled END WHERE id = :agentId",
    )
    fun setSnapshotState(
        agentId: String,
        cardHash: String,
        disableAgent: Boolean,
    ): Int

    @Transaction
    fun replaceForAgent(
        agentId: String,
        capabilities: List<A2aCapabilityEntity>,
    ) {
        deleteByAgent(agentId)
        insertAll(capabilities)
    }

    @Transaction
    fun replaceSnapshot(
        agentId: String,
        cardHash: String,
        disableAgent: Boolean,
        capabilities: List<A2aCapabilityEntity>,
    ) {
        check(setSnapshotState(agentId, cardHash, disableAgent) == 1) { "A2A agent not found: $agentId" }
        replaceForAgent(agentId, capabilities)
    }
}

@Dao
interface A2aTaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(task: A2aTaskEntity)

    @Query("SELECT * FROM a2a_tasks WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): A2aTaskEntity?

    @Query(
        "SELECT * FROM a2a_tasks WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY updatedAtEpochMillis",
    )
    fun listUnsettled(): List<A2aTaskEntity>

    @Query(
        "UPDATE a2a_tasks SET taskId = :taskId, contextId = :contextId, " +
            "lastEventSequence = :sequence, lastEventId = :eventId, " +
            "state = :state, deliveryState = :deliveryState, " +
            "updatedAtEpochMillis = :updatedAt WHERE toolCallId = :toolCallId",
    )
    @Suppress("LongParameterList") // one column-bound parameter per durable remote Task transition
    fun updateRemoteState(
        toolCallId: String,
        taskId: String?,
        contextId: String?,
        sequence: Long,
        eventId: String?,
        state: String,
        deliveryState: String,
        updatedAt: Long,
    ): Int
}
