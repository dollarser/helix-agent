package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
import com.helix.core.storage.entity.SkillEntity
import com.helix.core.storage.entity.SkillSnapshotEntity

@Dao
interface McpServerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(server: McpServerEntity)

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    fun byId(id: String): McpServerEntity?

    @Query("SELECT * FROM mcp_servers ORDER BY rowid ASC")
    fun list(): List<McpServerEntity>

    @Query("UPDATE mcp_servers SET enabled = :enabled, trustState = :trustState WHERE id = :id")
    fun update(
        id: String,
        enabled: Boolean,
        trustState: String,
    )
}

@Dao
interface McpCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(capability: McpCapabilityEntity): Long

    @Query("SELECT * FROM mcp_capabilities WHERE serverId = :serverId ORDER BY rowid ASC")
    fun listByServer(serverId: String): List<McpCapabilityEntity>

    @Query("UPDATE mcp_capabilities SET enabled = :enabled WHERE rowId = :rowId")
    fun setEnabled(
        rowId: Long,
        enabled: Boolean,
    )
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(skill: SkillEntity)

    @Query("SELECT * FROM skills WHERE id = :id")
    fun byId(id: String): SkillEntity?

    @Query("SELECT * FROM skills ORDER BY name ASC")
    fun list(): List<SkillEntity>

    @Query("UPDATE skills SET enabled = :enabled WHERE id = :id")
    fun setEnabled(
        id: String,
        enabled: Boolean,
    )
}

@Dao
interface SkillSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(snapshot: SkillSnapshotEntity)

    @Query("SELECT * FROM skill_snapshots WHERE runId = :runId ORDER BY rowid ASC")
    fun listByRun(runId: String): List<SkillSnapshotEntity>
}
