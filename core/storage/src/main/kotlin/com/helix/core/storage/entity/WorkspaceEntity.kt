package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Resource identity is independent of the session, optional project, locator and live availability. */
@Entity(
    tableName = "workspaces",
    indices = [Index(value = ["identityKey"], unique = true), Index(value = ["ownerSessionId"], unique = true)],
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val backend: String,
    val locator: String,
    val identityKey: String,
    val ownership: String,
    val availability: String,
    val witness: String?,
    val projectId: String?,
    val ownerSessionId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "session_workspaces",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("workspaceId")],
)
data class SessionWorkspaceEntity(
    @PrimaryKey val sessionId: String,
    val workspaceId: String,
    val relativePath: String,
    val revision: Long,
)

/** The resource/revision actually advertised to one model request; never overwritten on a switch. */
@Entity(
    tableName = "model_call_workspaces",
    foreignKeys = [
        ForeignKey(
            entity = ModelCallEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelCallId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("workspaceId")],
)
data class ModelCallWorkspaceEntity(
    @PrimaryKey val modelCallId: String,
    val workspaceId: String,
    val relativePath: String,
    val bindingRevision: Long,
)
