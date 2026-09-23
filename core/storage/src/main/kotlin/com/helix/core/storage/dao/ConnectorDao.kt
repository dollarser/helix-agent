package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ConnectorCatalogStateEntity
import com.helix.core.storage.entity.ConnectorInstallationEntity
import com.helix.core.storage.entity.ConnectorSkillOwnershipEntity
import com.helix.core.storage.entity.SessionConnectorEntity

@Dao
@Suppress("TooManyFunctions") // cohesive Room installation and selection operations
interface ConnectorDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun claimEndpoint(row: com.helix.core.storage.entity.ConnectorEndpointEntity)

    @Query("SELECT id FROM connector_endpoints")
    fun endpointIds(): List<String>

    @Query("SELECT * FROM connector_installations ORDER BY id")
    fun installations(): List<ConnectorInstallationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: ConnectorInstallationEntity)

    @Query(
        "UPDATE connector_installations SET revision = :revision, manifest = :manifest WHERE id = " +
            ":id AND revision = :expected",
    )
    fun replace(
        id: String,
        expected: Long,
        revision: Long,
        manifest: String,
    ): Int

    @Query("DELETE FROM connector_installations WHERE id = :id AND revision = :expected")
    fun delete(
        id: String,
        expected: Long,
    ): Int

    @Query("UPDATE connector_installations SET defaultSelected = :selected WHERE id = :id")
    fun setDefault(
        id: String,
        selected: Boolean,
    ): Int

    @Query("SELECT * FROM connector_skill_ownership")
    fun ownerships(): List<ConnectorSkillOwnershipEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun claim(row: ConnectorSkillOwnershipEntity)

    @Query(
        "UPDATE connector_skill_ownership SET independent = :independent WHERE source = :source AND " +
            "name = :name AND hash = :hash",
    )
    fun setIndependent(
        source: String,
        name: String,
        hash: String,
        independent: Boolean,
    )

    @Query("SELECT connectorId FROM session_connectors WHERE sessionId = :sessionId ORDER BY connectorId")
    fun selected(sessionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun select(row: SessionConnectorEntity)

    @Query("DELETE FROM session_connectors WHERE sessionId = :sessionId AND connectorId = :connectorId")
    fun deselect(
        sessionId: String,
        connectorId: String,
    )

    @Query("DELETE FROM session_connectors WHERE sessionId = :sessionId")
    fun clearSession(sessionId: String)

    @Query(
        "INSERT OR IGNORE INTO session_connectors(sessionId, connectorId) SELECT :sessionId, id " +
            "FROM connector_installations WHERE defaultSelected = 1",
    )
    fun snapshotDefaults(sessionId: String)

    @Query("SELECT COUNT(*) FROM connector_catalog_state WHERE id = 'legacy-imported'")
    fun migrated(): Int

    @Insert
    fun markMigrated(row: ConnectorCatalogStateEntity)
}
