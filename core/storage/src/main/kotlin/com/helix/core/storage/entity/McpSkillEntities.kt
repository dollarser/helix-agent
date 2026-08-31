package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** architecture doc 9.1: `mcp_servers` — endpoint/command ref and auth alias, never credentials. */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val transport: String,
    val endpointRef: String?,
    val commandRef: String?,
    val authAlias: String?,
    val enabled: Boolean,
    val trustState: String,
)

/** architecture doc 9.1: `mcp_capabilities` — protocolVersion, kind, name, schemaHash, enabled. */
@Entity(
    tableName = "mcp_capabilities",
    foreignKeys =
        [
            ForeignKey(
                entity = McpServerEntity::class,
                parentColumns = ["id"],
                childColumns = ["serverId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["serverId", "kind", "name"], unique = true)],
)
data class McpCapabilityEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long,
    val serverId: String,
    val protocolVersion: String,
    val kind: String,
    val name: String,
    val schemaHash: String,
    val enabled: Boolean,
)

/** architecture doc 9.1: `skills` — rootRef + contentHash. */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val source: String,
    val version: String,
    val rootRef: String,
    val contentHash: String,
    val enabled: Boolean,
)

/**
 * architecture doc 9.1: `skill_snapshots` — runId, skillId, contentHash, catalogEntry. A run
 * snapshots many skills, so the key is the (runId, skillId) pair.
 */
@Entity(
    tableName = "skill_snapshots",
    primaryKeys = ["runId", "skillId"],
    foreignKeys =
        [
            ForeignKey(
                entity = SkillEntity::class,
                parentColumns = ["id"],
                childColumns = ["skillId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class SkillSnapshotEntity(
    val runId: String,
    val skillId: String,
    val contentHash: String,
    val catalogEntry: String,
)
