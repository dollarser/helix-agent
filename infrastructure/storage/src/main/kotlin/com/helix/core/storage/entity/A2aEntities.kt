package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A2A configuration; [authAlias] is a SecretStore alias, never the credential value. */
@Entity(tableName = "a2a_agents")
data class A2aAgentEntity(
    @PrimaryKey val id: String,
    val endpointRef: String,
    val authAlias: String?,
    val enabled: Boolean,
    val cardHash: String?,
)

/** One bounded Skill entry from the Agent Card snapshot selected for this Agent. */
@Entity(
    tableName = "a2a_capabilities",
    foreignKeys =
        [
            ForeignKey(
                entity = A2aAgentEntity::class,
                parentColumns = ["id"],
                childColumns = ["agentId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["agentId", "skillId"], unique = true)],
)
data class A2aCapabilityEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long,
    val agentId: String,
    val interfaceUrl: String,
    val binding: String,
    val protocolVersion: String,
    val tenant: String?,
    val skillId: String,
    val skillHash: String,
    val inputModes: String,
    val outputModes: String,
    val enabled: Boolean,
)

/** Durable correlation for one local ToolCall and at most one remote A2A Task. */
@Entity(
    tableName = "a2a_tasks",
    foreignKeys =
        [
            ForeignKey(
                entity = ToolCallEntity::class,
                parentColumns = ["id"],
                childColumns = ["toolCallId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["taskId"]), Index(value = ["state"])],
)
data class A2aTaskEntity(
    @PrimaryKey val toolCallId: String,
    val agentId: String,
    val skillId: String,
    val cardHash: String,
    val skillHash: String,
    val inputHash: String,
    val interfaceUrl: String,
    val binding: String,
    val protocolVersion: String,
    val tenant: String?,
    val taskId: String?,
    val contextId: String?,
    val lastEventSequence: Long,
    val lastEventId: String?,
    val state: String,
    val deliveryState: String,
    val updatedAtEpochMillis: Long,
)
