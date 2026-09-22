package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Accepted input is not history until its USER message and consumption mapping commit together. */
@Entity(
    tableName = "session_inputs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = TurnEntity::class, parentColumns = ["id"], childColumns = ["expectedTurnId"]),
        ForeignKey(entity = TurnEntity::class, parentColumns = ["id"], childColumns = ["consumedTurnId"]),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"]),
        ForeignKey(entity = ModelCallEntity::class, parentColumns = ["id"], childColumns = ["requestModelCallId"]),
    ],
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index(value = ["sessionId", "state", "delivery", "sequence"]),
        Index("expectedTurnId"), Index("consumedTurnId"), Index(value = ["messageId"], unique = true),
        Index("requestModelCallId"), Index("textRef"),
    ],
)
data class SessionInputEntity(
    @PrimaryKey val inputId: String,
    val schemaVersion: Int,
    val sessionId: String,
    val sequence: Long,
    val delivery: String,
    val expectedTurnId: String?,
    val revision: Long,
    val textRef: String,
    val textBytes: Long,
    val providerId: String,
    val modelId: String,
    val mode: String,
    val configurationFingerprint: String,
    val state: String,
    val consumedTurnId: String?,
    val messageId: String?,
    val requestModelCallId: String?,
    val blockedReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** NO_ACTION protects accepted attachment references from independent artifact deletion. */
@Entity(
    tableName = "session_input_attachments",
    primaryKeys = ["inputId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SessionInputEntity::class,
            parentColumns = ["inputId"],
            childColumns = ["inputId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = ArtifactEntity::class, parentColumns = ["id"], childColumns = ["artifactId"]),
    ],
    indices = [Index("artifactId"), Index(value = ["inputId", "artifactId"], unique = true)],
)
data class SessionInputAttachmentEntity(
    val inputId: String,
    val ordinal: Int,
    val artifactId: String,
    val boundSha256: String,
)
