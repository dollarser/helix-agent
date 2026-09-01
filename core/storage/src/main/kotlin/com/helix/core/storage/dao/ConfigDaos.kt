package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.CapabilityGrantEntity
import com.helix.core.storage.entity.ExecutionTargetEntity
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.RuntimeInstallEntity

@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun append(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE id = :id")
    fun byId(id: String): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE correlationId = :correlationId ORDER BY timestamp ASC, rowid ASC")
    fun listByCorrelation(correlationId: String): List<AuditEventEntity>

    /**
     * The NEWEST [limit] rows, newest first — the bounded load of the audit log page
     * (roadmap HXA-036; security doc section 10: the page never loads the whole table).
     * `rowid` breaks timestamp ties so the page is deterministic.
     */
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC, rowid DESC LIMIT :limit")
    fun recent(limit: Int): List<AuditEventEntity>
}

@Dao
interface ProviderConfigDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(config: ProviderConfigEntity)

    /**
     * Explicit overwrite: updates the existing row for the same id IN PLACE.
     *
     * Deliberately NOT [OnConflictStrategy.REPLACE]: an `INSERT OR REPLACE` is a
     * DELETE+INSERT under the hood, and `sessions.providerId` references this table
     * with `ON DELETE SET NULL` — editing ANY provider used to silently UNBIND every
     * session pointing at it (the user changed an endpoint and all their sessions lost
     * their provider). An in-place UPDATE keeps the row identity: the FK targets never
     * see a delete, so no SET NULL fires. Eight scalar parameters because Room @Query
     * cannot bind an entity's property paths: the UPDATE carries the row's columns
     * explicitly (identity-preserving overwrite).
     */
    @Suppress("LongParameterList")
    @Query(
        "UPDATE provider_configs SET displayName = :displayName, protocol = :protocol, " +
            "endpoint = :endpoint, model = :model, headersJson = :headersJson, " +
            "secretAlias = :secretAlias, capabilitySnapshot = :capabilitySnapshot " +
            "WHERE id = :id",
    )
    fun update(
        id: String,
        displayName: String,
        protocol: String,
        endpoint: String,
        model: String,
        headersJson: String,
        secretAlias: String,
        capabilitySnapshot: String,
    ): Int

    /** Rows are deleted only through the repository's explicit delete; returns affected count. */
    @Query("DELETE FROM provider_configs WHERE id = :id")
    fun delete(id: String): Int

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    fun byId(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs ORDER BY displayName ASC")
    fun list(): List<ProviderConfigEntity>
}

@Dao
interface RuntimeInstallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(install: RuntimeInstallEntity)

    @Query("SELECT * FROM runtime_installs WHERE id = :id")
    fun byId(id: String): RuntimeInstallEntity?

    @Query("SELECT * FROM runtime_installs ORDER BY installedAt DESC")
    fun list(): List<RuntimeInstallEntity>

    @Query("UPDATE runtime_installs SET state = :state WHERE id = :id")
    fun updateState(
        id: String,
        state: String,
    )
}

@Dao
interface ExecutionTargetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(target: ExecutionTargetEntity)

    @Query("SELECT * FROM execution_targets WHERE id = :id")
    fun byId(id: String): ExecutionTargetEntity?

    @Query("SELECT * FROM execution_targets ORDER BY rowid ASC")
    fun list(): List<ExecutionTargetEntity>
}

@Dao
interface CapabilityGrantDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(grant: CapabilityGrantEntity): Long

    @Query("SELECT * FROM capability_grants WHERE rowId = :rowId")
    fun byRowId(rowId: Long): CapabilityGrantEntity?

    @Query("SELECT * FROM capability_grants WHERE type = :type ORDER BY checkedAt DESC")
    fun listByType(type: String): List<CapabilityGrantEntity>
}
