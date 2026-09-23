package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Only this row publishes an installation. The manifest contains references, never Secrets. */
@Entity(tableName = "connector_installations", indices = [Index(value = ["identity"], unique = true)])
data class ConnectorInstallationEntity(
    @PrimaryKey val id: String,
    val identity: String,
    val revision: Long,
    val manifest: String,
    val defaultSelected: Boolean,
)

@Entity(tableName = "connector_skill_ownership", primaryKeys = ["source", "name", "hash"])
data class ConnectorSkillOwnershipEntity(
    val source: String,
    val name: String,
    val hash: String,
    val independent: Boolean,
)

/** No installation FK: missing packages remain visibly unavailable, never rebound by name. */
@Entity(
    tableName = "session_connectors",
    primaryKeys = ["sessionId", "connectorId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionConnectorEntity(
    val sessionId: String,
    val connectorId: String,
)

@Entity(tableName = "connector_catalog_state")
data class ConnectorCatalogStateEntity(
    @PrimaryKey val id: String,
)

/** Retained endpoint identity enables cleanup and rejects stale bridges after replacement. */
@Entity(tableName = "connector_endpoints")
data class ConnectorEndpointEntity(
    @PrimaryKey val id: String,
)
